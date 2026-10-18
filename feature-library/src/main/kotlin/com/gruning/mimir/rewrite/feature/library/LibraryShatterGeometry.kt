package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirShatterMotion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * "Every hide shatters the card" (): the geometry, the physics and the
 * timeline of the destroyed card, with no Android type in it so a JVM test can hold all of it. The reference is the reference prototype (`card-destruction-prototype.html`, `makeShatter` / `drawShatter`); this is a port of
 * it, value for value, and `LibraryShatterGeometryTest` pins that against the prototype's own output.
 *
 * A card breaks the way its stable id says: the id seeds the generator, so the same game breaks into the same
 * shards every time, and two games do not. The card is cut into a Voronoi diagram of nineteen seeds (the impact
 * point and three rings around it) by clipping its rectangle with one half-plane per other seed. The pieces are
 * convex, they tile the card exactly, and they are worked out once per destroy: nothing here runs per frame except
 * the timeline and physics functions, and those return primitives.
 */

/** Where the card is struck: this fraction of its width and of its height. */
internal const val ShatterImpactX = 0.38
internal const val ShatterImpactY = 0.30

/** The rings of seeds around the impact, as a fraction of the card's longer side, and how many seeds each holds. */
private val ShatterRingRadius = doubleArrayOf(0.16, 0.34, 0.62)
private val ShatterRingCount = intArrayOf(5, 7, 6)

/** A seed sits on its ring within +-0.25 rad of its slot and at 0.8 to 1.2 times the ring's radius. */
private const val ShatterAngleJitterRad = 0.5
private const val ShatterRadiusJitterMin = 0.8
private const val ShatterRadiusJitterSpan = 0.4

/** Shards leave at 0.55 to 1.5 card widths per second, away from the impact, and spin up to 2.8 rad/s either way. */
private const val ShatterShardSpeedMin = 0.55
private const val ShatterShardSpeedSpan = 0.95
private const val ShatterShardSpinMax = 2.8

/** The shards fall at 2.4 card heights per second squared; the sparks at half that again (the prototype's 0.6 h s^2). */
internal const val ShatterShardGravityHeights = 2.4f
internal const val ShatterSparkGravityHeights = 1.2f

/** Twenty-two sparks leave the point of impact at 0.9 to 2.5 card widths per second, biased up by 0.4. */
internal const val ShatterSparkCount = 22
private const val ShatterSparkSpeedMin = 0.9
private const val ShatterSparkSpeedSpan = 1.6
private const val ShatterSparkLift = 0.4

/** A spark is 1 to 2.8 dp across its radius: dp, so the caller scales it by the screen's density. */
private const val ShatterSparkRadiusMinDp = 1.0
private const val ShatterSparkRadiusSpanDp = 1.8

/**
 * The prototype leaves two seeds on one point when both are clamped into the same corner of a small card, and
 * both then own the whole card. It happens in about one card in 1,600. A seed that lands within [ShatterMinSeedGapPx]
 * of an earlier one is stepped [ShatterSeedNudgePx] along the golden angle until it is clear, so the card still has
 * nineteen pieces that tile it. It draws nothing from the generator, so every card that never collided is the
 * prototype's own, exactly.
 */
private const val ShatterMinSeedGapPx = 2.0
private const val ShatterSeedNudgePx = 3.0
private const val ShatterSeedNudgeAttempts = 8
private const val GoldenAngleRad = 2.399963229728653

/** Two points closer than this in both axes are one point; a seed's twin closer than this squared is the seed. */
private const val RepeatEpsilon = 1e-9
private const val CoincidentSeedSquared = 1e-12

private const val RadiansToDegrees = 57.29578f

/** A convex polygon in the card's own pixels. The outline of one shard; winding is the card rectangle's own. */
internal class ShatterPolygon(val x: DoubleArray, val y: DoubleArray) {
    val size: Int get() = x.size

    /** Positive for the winding of the card's rectangle (clockwise on screen). */
    fun area(): Double {
        var twice = 0.0
        for (i in 0 until size) {
            val j = if (i + 1 == size) 0 else i + 1
            twice += x[i] * y[j] - x[j] * y[i]
        }
        return twice / 2.0
    }

    /** Every turn goes the same way (collinear points allowed), so no vertex points into the piece. */
    fun isConvex(epsilon: Double = 1e-7): Boolean {
        if (size < 3) return false
        for (i in 0 until size) {
            val j = if (i + 1 == size) 0 else i + 1
            val k = if (j + 1 == size) 0 else j + 1
            val cross = (x[j] - x[i]) * (y[k] - y[j]) - (y[j] - y[i]) * (x[k] - x[j])
            if (cross < -epsilon) return false
        }
        return true
    }
}

/**
 * One piece of the card and how it flies. [outline] is in card pixels; [centreX], [centreY] is the mean of its
 * corners (the prototype's own centre, which it spins about); [dirX], [dirY] is the unit vector away from the
 * impact; [distance] is how far the piece's centre is from the impact. [delayMs] is when it leaves, from the start
 * of the shatter; [crackAtMs] is when the crack that outlines it starts to show.
 */
internal class ShatterShard(
    val outline: ShatterPolygon,
    val centreX: Float,
    val centreY: Float,
    val dirX: Float,
    val dirY: Float,
    val distance: Float,
    val speedPxPerS: Float,
    val spinRadPerS: Float,
    val delayMs: Float,
    val crackAtMs: Float,
) {
    /** How far along x the piece has travelled [tauSeconds] after it left. */
    fun travelX(tauSeconds: Float): Float = dirX * speedPxPerS * tauSeconds

    /** How far along y: away from the impact, and down under [gravityPxPerS2]. */
    fun travelY(tauSeconds: Float, gravityPxPerS2: Float): Float =
        dirY * speedPxPerS * tauSeconds + 0.5f * gravityPxPerS2 * tauSeconds * tauSeconds

    /** How far it has turned about its centre, in the degrees a canvas rotates by. */
    fun turnDegrees(tauSeconds: Float): Float = spinRadPerS * tauSeconds * RadiansToDegrees
}

/** One spark from the impact: the speed it leaves at (px/s), how long it lives from the strike's end, and its size. */
internal class ShatterSpark(
    val vx: Float,
    val vy: Float,
    val lifeMs: Float,
    val radiusDp: Float,
)

/**
 * A card's whole destruction, worked out once. [width], [height] are the card's size in pixels; [impactX], [impactY]
 * is where it is struck; [seedX], [seedY] are the nineteen seeds the pieces grew from (the impact first).
 */
internal class ShatterPlan(
    val width: Float,
    val height: Float,
    val impactX: Float,
    val impactY: Float,
    val seedX: DoubleArray,
    val seedY: DoubleArray,
    val shards: Array<ShatterShard>,
    val sparks: Array<ShatterSpark>,
    val shardGravityPxPerS2: Float,
    val sparkGravityPxPerS2: Float,
)

/** FNV-1a over the id's UTF-16 units: the prototype's `hash`, so an id seeds the same generator it always did. */
internal fun shatterSeedOf(stableId: String): Int {
    var hash = -2128831035 // FNV-1a offset basis (0x811C9DC5), signed Int.
    for (unit in stableId) {
        hash = hash xor unit.code
        hash *= 16777619
    }
    return hash
}

/** The prototype's mulberry32, on `Int` arithmetic: `ushr` for `>>>` and a wrapping multiply for `Math.imul`. */
internal class ShatterRandom(seed: Int) {
    private var state = seed

    fun nextDouble(): Double {
        state += 1831565813 // Mulberry32 increment (0x6D2B79F5).
        var t = state
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return ((t xor (t ushr 14)).toLong() and 4294967295L).toDouble() / 4294967296.0
    }
}

private fun clampSeed(value: Double, extent: Double): Double = min(extent - 1.0, max(1.0, value))

private fun anySeedWithin(seedX: DoubleArray, seedY: DoubleArray, count: Int, x: Double, y: Double, gap: Double): Boolean {
    for (i in 0 until count) {
        val dx = seedX[i] - x
        val dy = seedY[i] - y
        if (dx * dx + dy * dy < gap * gap) return true
    }
    return false
}

/** Keeps the part of [poly] on the side of the line `nx*x + ny*y = c0` where `nx*x + ny*y <= c0`. */
private fun clipHalfPlane(poly: ShatterPolygon, nx: Double, ny: Double, c0: Double): ShatterPolygon {
    val n = poly.size
    val outX = DoubleArray(2 * n)
    val outY = DoubleArray(2 * n)
    var count = 0
    for (i in 0 until n) {
        val j = if (i + 1 == n) 0 else i + 1
        val ax = poly.x[i]
        val ay = poly.y[i]
        val bx = poly.x[j]
        val by = poly.y[j]
        val da = nx * ax + ny * ay - c0
        val db = nx * bx + ny * by - c0
        val aInside = da <= 0.0
        if (aInside) {
            outX[count] = ax
            outY[count] = ay
            count++
        }
        if (aInside != (db <= 0.0)) {
            val t = da / (da - db)
            outX[count] = ax + (bx - ax) * t
            outY[count] = ay + (by - ay) * t
            count++
        }
    }
    return ShatterPolygon(outX.copyOf(count), outY.copyOf(count))
}

/** Clipping leaves a corner twice when a vertex sits on the line; a repeated corner is one corner. */
private fun ShatterPolygon.withoutRepeats(): ShatterPolygon {
    val outX = DoubleArray(size)
    val outY = DoubleArray(size)
    var count = 0
    for (i in 0 until size) {
        if (count > 0 && abs(x[i] - outX[count - 1]) < RepeatEpsilon && abs(y[i] - outY[count - 1]) < RepeatEpsilon) continue
        outX[count] = x[i]
        outY[count] = y[i]
        count++
    }
    while (count > 1 && abs(outX[0] - outX[count - 1]) < RepeatEpsilon && abs(outY[0] - outY[count - 1]) < RepeatEpsilon) count--
    return ShatterPolygon(outX.copyOf(count), outY.copyOf(count))
}

/**
 * The Voronoi diagram of [seedX], [seedY] over the rectangle 0..[width] x 0..[height], one cell per seed, by clipping
 * the rectangle with the bisector of the seed and every other one. O(n^2) in the seeds, and n is 19. Two seeds on one
 * point cannot both own it: the later one gets no cell, so the cells always tile the rectangle exactly.
 */
internal fun shatterVoronoiCells(width: Double, height: Double, seedX: DoubleArray, seedY: DoubleArray): List<ShatterPolygon> {
    val cells = ArrayList<ShatterPolygon>(seedX.size)
    val rectX = doubleArrayOf(0.0, width, width, 0.0)
    val rectY = doubleArrayOf(0.0, 0.0, height, height)
    cellLoop@ for (i in seedX.indices) {
        var poly = ShatterPolygon(rectX, rectY)
        var j = 0
        while (j < seedX.size && poly.size >= 3) {
            if (j != i) {
                val dx = seedX[j] - seedX[i]
                val dy = seedY[j] - seedY[i]
                if (dx * dx + dy * dy < CoincidentSeedSquared) {
                    if (j < i) continue@cellLoop
                } else {
                    val c0 = (seedX[j] * seedX[j] + seedY[j] * seedY[j] - seedX[i] * seedX[i] - seedY[i] * seedY[i]) / 2.0
                    poly = clipHalfPlane(poly, dx, dy, c0)
                }
            }
            j++
        }
        poly = poly.withoutRepeats()
        if (poly.size >= 3) cells.add(poly)
    }
    return cells
}

/**
 * Breaks a card [width] x [height] pixels, identified by [stableId], into its shards and sparks. Deterministic: the
 * same three arguments always give the same plan. The order the generator is drawn in is the prototype's: for each
 * ring its offset and then each seed's angle and radius jitter; for each shard its speed and spin (after two draws
 * for a direction, in the case that a shard's centre is the impact itself); for each spark its angle, its speed, its
 * life and its size.
 */
internal fun buildShatterPlan(width: Float, height: Float, stableId: String): ShatterPlan {
    val w = width.toDouble()
    val h = height.toDouble()
    val random = ShatterRandom(shatterSeedOf(stableId))
    val impactX = w * ShatterImpactX
    val impactY = h * ShatterImpactY
    val longSide = max(w, h)

    val seedCount = 1 + ShatterRingCount.sum()
    val seedX = DoubleArray(seedCount)
    val seedY = DoubleArray(seedCount)
    seedX[0] = impactX
    seedY[0] = impactY
    var seeds = 1
    for (ring in ShatterRingRadius.indices) {
        val offset = random.nextDouble() * PI * 2.0
        val count = ShatterRingCount[ring]
        for (i in 0 until count) {
            val angle = offset + (i.toDouble() / count) * PI * 2.0 + (random.nextDouble() - 0.5) * ShatterAngleJitterRad
            val ringDistance = ShatterRingRadius[ring] * longSide *
                (ShatterRadiusJitterMin + random.nextDouble() * ShatterRadiusJitterSpan)
            var x = clampSeed(impactX + cos(angle) * ringDistance, w)
            var y = clampSeed(impactY + sin(angle) * ringDistance, h)
            var attempt = 0
            while (attempt < ShatterSeedNudgeAttempts && anySeedWithin(seedX, seedY, seeds, x, y, ShatterMinSeedGapPx)) {
                val turn = attempt * GoldenAngleRad
                x = clampSeed(x + cos(turn) * ShatterSeedNudgePx, w)
                y = clampSeed(y + sin(turn) * ShatterSeedNudgePx, h)
                attempt++
            }
            seedX[seeds] = x
            seedY[seeds] = y
            seeds++
        }
    }

    val cells = shatterVoronoiCells(w, h, seedX, seedY)
    val centreX = DoubleArray(cells.size)
    val centreY = DoubleArray(cells.size)
    val dirX = DoubleArray(cells.size)
    val dirY = DoubleArray(cells.size)
    val distance = DoubleArray(cells.size)
    val speed = DoubleArray(cells.size)
    val spin = DoubleArray(cells.size)
    var farthest = 1.0
    for (i in cells.indices) {
        val cell = cells[i]
        var cx = 0.0
        var cy = 0.0
        for (k in 0 until cell.size) {
            cx += cell.x[k]
            cy += cell.y[k]
        }
        cx /= cell.size
        cy /= cell.size
        var dx = cx - impactX
        var dy = cy - impactY
        val away = hypot(dx, dy)
        if (away < 1e-3) {
            dx = random.nextDouble() - 0.5
            dy = -1.0
        }
        val norm = hypot(dx, dy).let { if (it == 0.0) 1.0 else it }
        farthest = max(farthest, away)
        centreX[i] = cx
        centreY[i] = cy
        dirX[i] = dx / norm
        dirY[i] = dy / norm
        distance[i] = away
        speed[i] = (ShatterShardSpeedMin + random.nextDouble() * ShatterShardSpeedSpan) * w
        spin[i] = (random.nextDouble() * 2.0 - 1.0) * ShatterShardSpinMax
    }
    val shards = Array(cells.size) { i ->
        val reach = distance[i] / farthest
        ShatterShard(
            outline = cells[i],
            centreX = centreX[i].toFloat(),
            centreY = centreY[i].toFloat(),
            dirX = dirX[i].toFloat(),
            dirY = dirY[i].toFloat(),
            distance = distance[i].toFloat(),
            speedPxPerS = speed[i].toFloat(),
            spinRadPerS = spin[i].toFloat(),
            delayMs = (MimirShatterMotion.StrikeMs + reach * MimirShatterMotion.ShardDelaySpreadMs).toFloat(),
            crackAtMs = (reach * MimirShatterMotion.CrackSpreadMs).toFloat(),
        )
    }

    val sparks = Array(ShatterSparkCount) {
        val angle = random.nextDouble() * PI * 2.0
        val sparkSpeed = (ShatterSparkSpeedMin + random.nextDouble() * ShatterSparkSpeedSpan) * w
        ShatterSpark(
            vx = (cos(angle) * sparkSpeed).toFloat(),
            vy = (sin(angle) * sparkSpeed - ShatterSparkLift * w).toFloat(),
            lifeMs = (MimirShatterMotion.SparkLifeMinMs + random.nextDouble() * MimirShatterMotion.SparkLifeSpanMs).toFloat(),
            radiusDp = (ShatterSparkRadiusMinDp + random.nextDouble() * ShatterSparkRadiusSpanDp).toFloat(),
        )
    }

    return ShatterPlan(
        width = width,
        height = height,
        impactX = impactX.toFloat(),
        impactY = impactY.toFloat(),
        seedX = seedX,
        seedY = seedY,
        shards = shards,
        sparks = sparks,
        shardGravityPxPerS2 = ShatterShardGravityHeights * height,
        sparkGravityPxPerS2 = ShatterSparkGravityHeights * height,
    )
}

/** Which part of the destruction a moment falls in. */
internal enum class ShatterPhase { Strike, Shards, Done }

/**
 * The destruction's timeline, as functions of the milliseconds since it started. Each returns a primitive and
 * allocates nothing, so the overlay calls them from its draw with the one clock it reads there.
 */
internal object ShatterTimeline {
    /** The flash's peak alpha, a half sine over the strike. */
    const val FlashPeakAlpha = 0.5f

    /** The cracks' alpha once they have faded in. */
    const val CrackPeakAlpha = 0.85f

    /** The light along each shard's edge when the shards leave, and how it fades to nothing by the glow's end. */
    const val EdgeGlowPeakAlpha = 0.6f

    private val strikeMs = MimirShatterMotion.StrikeMs.toFloat()
    private val crackFadeMs = MimirShatterMotion.CrackFadeMs.toFloat()
    private val shardFadeFromMs = MimirShatterMotion.ShardFadeFromMs.toFloat()
    private val edgeGlowEndMs = MimirShatterMotion.EdgeGlowEndMs.toFloat()
    private val endMs = MimirShatterMotion.EndMs.toFloat()

    fun phaseAt(elapsedMs: Float): ShatterPhase = when {
        elapsedMs < strikeMs -> ShatterPhase.Strike
        elapsedMs < endMs -> ShatterPhase.Shards
        else -> ShatterPhase.Done
    }

    /** 0 before [fromMs], 1 from [toMs], linear between. */
    fun progress(elapsedMs: Float, fromMs: Float, toMs: Float): Float =
        if (toMs <= fromMs) {
            if (elapsedMs >= fromMs) 1f else 0f
        } else {
            ((elapsedMs - fromMs) / (toMs - fromMs)).coerceIn(0f, 1f)
        }

    /** The warm flash on the card: 0.5 at half the strike, nothing before it and nothing after it. */
    fun flashAlpha(elapsedMs: Float): Float =
        if (elapsedMs <= 0f || elapsedMs >= strikeMs) 0f else FlashPeakAlpha * sin(PI.toFloat() * elapsedMs / strikeMs)

    /** A crack that starts to show at [crackAtMs] is fully in [MimirShatterMotion.CrackFadeMs] later. */
    fun crackAlpha(elapsedMs: Float, crackAtMs: Float): Float =
        CrackPeakAlpha * ((elapsedMs - crackAtMs) / crackFadeMs).coerceIn(0f, 1f)

    /** A shard is whole until 260 ms, then fades in on the square of the way to the end: `1 - easeIn`. */
    fun shardAlpha(elapsedMs: Float): Float {
        val p = progress(elapsedMs, shardFadeFromMs, endMs)
        return 1f - p * p
    }

    /** The glow along the shards' edges: 0.6 as they leave, gone by 400 ms. */
    fun edgeGlowAlpha(elapsedMs: Float): Float =
        EdgeGlowPeakAlpha * (1f - progress(elapsedMs, strikeMs, edgeGlowEndMs))

    /** The seconds a shard that leaves at [delayMs] has been flying. */
    fun shardSeconds(elapsedMs: Float, delayMs: Float): Float = max(0f, elapsedMs - delayMs) / 1000f

    /** The milliseconds a spark has lived: sparks start as the strike ends. */
    fun sparkAgeMs(elapsedMs: Float): Float = elapsedMs - strikeMs

    /** A spark fades linearly over its life; outside it, it is not there. */
    fun sparkAlpha(ageMs: Float, lifeMs: Float): Float =
        if (ageMs < 0f || ageMs > lifeMs) 0f else 1f - ageMs / lifeMs

    /** Where a spark that has lived [ageMs] is, along x, from the impact. */
    fun sparkOffsetX(spark: ShatterSpark, ageMs: Float): Float = spark.vx * (ageMs / 1000f)

    /** ...and along y, with its lift and the sparks' own gravity. */
    fun sparkOffsetY(spark: ShatterSpark, ageMs: Float, gravityPxPerS2: Float): Float {
        val seconds = ageMs / 1000f
        return spark.vy * seconds + 0.5f * gravityPxPerS2 * seconds * seconds
    }
}

/**
 * When everything around the shatter happens, in milliseconds from the moment it starts, and how long after the
 * hide prompt's A press that moment is. Under reduced motion there is no strike, no shards and no sparks: the card,
 * the gap and the hero cut, the pad is never held, and the receipt still shows, at once.
 */
internal data class ShatterSchedule(
    val startAfterPromptMs: Int,
    val overlayMs: Int,
    val heroHoldMs: Int,
    val heroCrossFadeMs: Int,
    val gapLeadMs: Int,
    val gapGlideMs: Int,
    val receiptAtMs: Int,
    val inputHoldMs: Int,
) {
    val playsOverlay: Boolean get() = overlayMs > 0

    /** The neighbours are seated: the moment the pad is released. */
    val gapClosedAtMs: Int get() = gapLeadMs + gapGlideMs
}

internal fun shatterSchedule(reducedMotion: Boolean): ShatterSchedule =
    if (reducedMotion) {
        ShatterSchedule(
            startAfterPromptMs = 0,
            overlayMs = 0,
            heroHoldMs = 0,
            heroCrossFadeMs = 0,
            gapLeadMs = 0,
            gapGlideMs = 0,
            receiptAtMs = 0,
            inputHoldMs = 0,
        )
    } else {
        ShatterSchedule(
            startAfterPromptMs = MimirMotion.OverlayDissolveCoverMs,
            overlayMs = MimirShatterMotion.EndMs,
            heroHoldMs = MimirShatterMotion.HeroHoldMs,
            heroCrossFadeMs = MimirShatterMotion.HeroCrossFadeMs,
            gapLeadMs = MimirShatterMotion.GapLeadMs,
            gapGlideMs = MimirShatterMotion.GapGlideMs,
            receiptAtMs = MimirShatterMotion.ReceiptAtMs,
            inputHoldMs = MimirShatterMotion.InputHoldMs,
        )
    }
