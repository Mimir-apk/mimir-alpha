package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirShatterMotion
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Every hide shatters the card" (walk-32h): the geometry, the physics, the timeline and the schedule, on the JVM with
 * no Compose. The goldens are the PROTOTYPE's own output (`card-destruction-prototype.html`, `makeShatter`), computed
 * by running its script in Node for the ids "ct", "hotd" and "jsr" on a 240 x 330 card: a port that agrees with them
 * to their printed digits is the prototype's geometry, value for value.
 */
class LibraryShatterGeometryTest {

    private val sizes = listOf(300f to 420f, 420f to 300f, 240f to 330f, 600f to 600f, 90f to 120f, 64f to 64f)

    private fun ids(count: Int): List<String> = (0 until count).map { "game-$it-${it * 7919 % 1013}" }

    private fun everyCard(check: (ShatterPlan, Float, Float, String) -> Unit) {
        for (id in ids(300)) for ((w, h) in sizes) check(buildShatterPlan(w, h, id), w, h, id)
    }

    @Test
    fun `the shards tile the card exactly`() = everyCard { plan, w, h, id ->
        val sum = plan.shards.sumOf { it.outline.area() }
        val card = w.toDouble() * h.toDouble()
        assertTrue("$id ${w}x$h: the areas sum to $sum, the card is $card", abs(sum - card) / card < 1e-9)
    }

    @Test
    fun `every shard is convex, has area, and sits inside the card`() = everyCard { plan, w, h, id ->
        for (shard in plan.shards) {
            val p = shard.outline
            assertTrue("$id ${w}x$h: a shard is not convex", p.isConvex())
            assertTrue("$id ${w}x$h: a shard has no area", p.area() > 0.0)
            for (k in 0 until p.size) {
                assertTrue("$id: x ${p.x[k]} outside 0..$w", p.x[k] >= -1e-9 && p.x[k] <= w + 1e-9)
                assertTrue("$id: y ${p.y[k]} outside 0..$h", p.y[k] >= -1e-9 && p.y[k] <= h + 1e-9)
            }
        }
    }

    @Test
    fun `a card breaks into nineteen shards from nineteen seeds, the impact first`() = everyCard { plan, w, h, id ->
        assertEquals("$id ${w}x$h", 19, plan.shards.size)
        assertEquals(19, plan.seedX.size)
        assertEquals(w * ShatterImpactX, plan.seedX[0], 1e-3)
        assertEquals(h * ShatterImpactY, plan.seedY[0], 1e-3)
        assertEquals(w * 0.38f, plan.impactX, 1e-3f)
        assertEquals(h * 0.30f, plan.impactY, 1e-3f)
    }

    @Test
    fun `two seeds never share a point, so no shard owns another's ground`() = everyCard { plan, w, h, id ->
        for (i in plan.seedX.indices) for (j in i + 1 until plan.seedX.size) {
            val gap = hypot(plan.seedX[i] - plan.seedX[j], plan.seedY[i] - plan.seedY[j])
            assertTrue("$id ${w}x$h: seeds $i and $j are $gap apart", gap >= 1.9)
        }
    }

    /**
     * The prototype leaves two seeds on one point when both clamp into one corner, and both then own the whole card:
     * for this card its own script yields 18 pieces, one seed having lost its cell, and 19 once the guard has moved the
     * twin. Found by running the prototype over 28,000 cards; it is one in about 1,600.
     */
    @Test
    fun `a card whose seeds collide still breaks into nineteen pieces that tile it`() {
        val plan = buildShatterPlan(300f, 420f, "game-791-550")
        assertEquals(19, plan.shards.size)
        assertEquals(300.0 * 420.0, plan.shards.sumOf { it.outline.area() }, 300.0 * 420.0 * 1e-9)
    }

    @Test
    fun `the same card always breaks the same way and another card does not`() {
        val a = buildShatterPlan(240f, 330f, "ct")
        val b = buildShatterPlan(240f, 330f, "ct")
        assertArrayEquals(a.seedX, b.seedX, 0.0)
        assertArrayEquals(a.seedY, b.seedY, 0.0)
        for (i in a.shards.indices) {
            assertArrayEquals(a.shards[i].outline.x, b.shards[i].outline.x, 0.0)
            assertArrayEquals(a.shards[i].outline.y, b.shards[i].outline.y, 0.0)
            assertEquals(a.shards[i].speedPxPerS, b.shards[i].speedPxPerS, 0f)
            assertEquals(a.shards[i].spinRadPerS, b.shards[i].spinRadPerS, 0f)
            assertEquals(a.shards[i].delayMs, b.shards[i].delayMs, 0f)
        }
        for (i in a.sparks.indices) {
            assertEquals(a.sparks[i].vx, b.sparks[i].vx, 0f)
            assertEquals(a.sparks[i].lifeMs, b.sparks[i].lifeMs, 0f)
        }
        val other = buildShatterPlan(240f, 330f, "hotd")
        assertFalse("two games broke into the same seeds", a.seedX.contentEquals(other.seedX))
    }

    @Test
    fun `the id seeds the generator the way the prototype's hash and mulberry32 do`() {
        assertEquals(1261010898, shatterSeedOf("ct"))
        assertEquals(2166136261L.toInt(), shatterSeedOf(""))
        assertEquals(1951559808, shatterSeedOf("Ünï"))
        val random = ShatterRandom(shatterSeedOf("ct"))
        val expected = doubleArrayOf(0.708730440354, 0.066969690612, 0.116932623088, 0.343510813545, 0.463898242917, 0.381542850286)
        for (value in expected) assertEquals(value, random.nextDouble(), 1e-11)
    }

    private fun assertGolden(
        id: String,
        areas: DoubleArray,
        seeds: List<Pair<Double, Double>>,
        firstShard: DoubleArray,
        lastShard: DoubleArray,
        firstSpark: DoubleArray,
        lastSpark: DoubleArray,
        firstVerts: Int,
        lastVerts: Int,
    ) {
        val plan = buildShatterPlan(240f, 330f, id)
        assertEquals("$id: shard count", areas.size, plan.shards.size)
        for (i in areas.indices) assertEquals("$id: area of shard $i", areas[i], plan.shards[i].outline.area(), 1e-3)
        seeds.forEachIndexed { i, (x, y) ->
            assertEquals("$id: seed $i x", x, plan.seedX[i], 1e-3)
            assertEquals("$id: seed $i y", y, plan.seedY[i], 1e-3)
        }
        fun shard(s: ShatterShard, g: DoubleArray, verts: Int, label: String) {
            assertEquals("$id $label: corners", verts, s.outline.size)
            assertEquals("$id $label: centre x", g[0], s.centreX.toDouble(), 1e-3)
            assertEquals("$id $label: centre y", g[1], s.centreY.toDouble(), 1e-3)
            assertEquals("$id $label: speed", g[2], s.speedPxPerS.toDouble(), 1e-2)
            assertEquals("$id $label: spin", g[3], s.spinRadPerS.toDouble(), 1e-4)
            assertEquals("$id $label: delay", g[4], s.delayMs.toDouble(), 1e-3)
            assertEquals("$id $label: crack", g[5], s.crackAtMs.toDouble(), 1e-3)
        }
        shard(plan.shards.first(), firstShard, firstVerts, "first")
        shard(plan.shards.last(), lastShard, lastVerts, "last")
        assertEquals(22, plan.sparks.size)
        fun spark(s: ShatterSpark, g: DoubleArray, label: String) {
            assertEquals("$id $label: vx", g[0], s.vx.toDouble(), 1e-2)
            assertEquals("$id $label: vy", g[1], s.vy.toDouble(), 1e-2)
            assertEquals("$id $label: life", g[2], s.lifeMs.toDouble(), 1e-2)
            assertEquals("$id $label: size", g[3], s.radiusDp.toDouble(), 1e-4)
        }
        spark(plan.sparks.first(), firstSpark, "spark 0")
        spark(plan.sparks.last(), lastSpark, "spark 21")
    }

    @Test
    fun `ct breaks as the prototype breaks it`() = assertGolden(
        id = "ct",
        areas = doubleArrayOf(2546.912, 3647.535, 4368.723, 5332.228, 3233.309, 2570.296, 4042.386, 1976.521, 1951.098, 1858.496, 4387.647, 5701.393, 13877.694, 8909.785, 7837.705, 3758.097, 1017.251, 956.451, 1226.474),
        seeds = listOf(91.2 to 99.0, 70.7200 to 59.2568, 132.5724 to 67.4369, 137.0671 to 132.0189),
        firstShard = doubleArrayOf(91.5454, 103.1812, 296.9414, -0.88281, 91.4492, 1.2421),
        lastShard = doubleArrayOf(231.9309, 38.1614, 264.5978, 1.85585, 142.9588, 45.3933),
        firstSpark = doubleArrayOf(-246.7217, -207.2394, 352.7753, 1.27967),
        lastSpark = doubleArrayOf(-71.6926, 288.8050, 450.1250, 1.27205),
        firstVerts = 5,
        lastVerts = 4,
    )

    @Test
    fun `hotd breaks as the prototype breaks it`() = assertGolden(
        id = "hotd",
        areas = doubleArrayOf(2685.352, 2568.131, 3071.007, 3982.371, 4254.072, 3598.414, 6688.777, 1659.655, 2533.356, 1009.653, 5732.208, 7801.117, 7195.766, 11880.148, 2999.528, 145.335, 1247.443, 1864.140, 8283.527),
        seeds = listOf(91.2 to 99.0, 121.3107 to 66.0877, 140.6420 to 115.2774, 87.8731 to 161.8670),
        firstShard = doubleArrayOf(89.9227, 101.1341, 253.2666, 0.32970, 90.8823, 0.7563),
        lastShard = doubleArrayOf(208.0483, 258.0044, 254.4897, -2.76238, 160.0, 60.0),
        firstSpark = doubleArrayOf(-227.8634, 65.0816, 409.9117, 1.63887),
        lastSpark = doubleArrayOf(408.6121, -441.3387, 304.1265, 1.69338),
        firstVerts = 5,
        lastVerts = 5,
    )

    @Test
    fun `jsr breaks as the prototype breaks it`() = assertGolden(
        id = "jsr",
        areas = doubleArrayOf(2692.204, 3958.669, 3293.224, 3340.831, 3479.368, 4769.554, 3398.219, 5662.527, 10296.297, 6192.492, 2756.852, 1835.782, 813.577, 3695.814, 1135.348, 1426.737, 2687.013, 4109.895, 13655.596),
        seeds = listOf(91.2 to 99.0, 145.5740 to 124.6926, 102.5034 to 160.6197, 55.6757 to 122.6779),
        firstShard = doubleArrayOf(96.0242, 101.1640, 146.1502, 1.84433, 91.8106, 1.5519),
        lastShard = doubleArrayOf(125.2449, 300.5603, 167.5860, -2.19771, 160.0, 60.0),
        firstSpark = doubleArrayOf(99.4218, 149.6849, 399.6341, 1.58412),
        lastSpark = doubleArrayOf(-313.9909, -16.1645, 432.9147, 2.52846),
        firstVerts = 5,
        lastVerts = 7,
    )

    @Test
    fun `every shard and spark is inside the ranges the spec names`() = everyCard { plan, w, _, id ->
        for (s in plan.shards) {
            assertTrue("$id: delay ${s.delayMs}", s.delayMs >= 90f - 1e-3f && s.delayMs <= 160f + 1e-3f)
            assertTrue("$id: crack ${s.crackAtMs}", s.crackAtMs >= 0f && s.crackAtMs <= 60f + 1e-3f)
            assertTrue("$id: speed ${s.speedPxPerS}", s.speedPxPerS >= 0.55f * w - 1e-2f && s.speedPxPerS <= 1.5f * w + 1e-2f)
            assertTrue("$id: spin ${s.spinRadPerS}", abs(s.spinRadPerS) <= 2.8f + 1e-4f)
            assertEquals("$id: unit direction", 1.0, hypot(s.dirX.toDouble(), s.dirY.toDouble()), 1e-5)
        }
        assertEquals(22, plan.sparks.size)
        for (p in plan.sparks) {
            assertTrue("$id: life ${p.lifeMs}", p.lifeMs >= 280f - 1e-3f && p.lifeMs <= 460f + 1e-3f)
            assertTrue("$id: radius ${p.radiusDp}", p.radiusDp >= 1f - 1e-4f && p.radiusDp <= 2.8f + 1e-4f)
            val speed = hypot(p.vx.toDouble(), (p.vy + 0.4f * w).toDouble())
            assertTrue("$id: spark speed $speed", speed >= 0.9 * w - 1e-2 && speed <= 2.5 * w + 1e-2)
        }
    }

    @Test
    fun `the farthest shard is the last to leave and the last to crack`() {
        val plan = buildShatterPlan(240f, 330f, "hotd")
        val far = plan.shards.maxBy { it.distance }
        assertEquals(160f, far.delayMs, 1e-3f)
        assertEquals(60f, far.crackAtMs, 1e-3f)
    }

    @Test
    fun `physics goes away from the impact, falls under 2 4 heights per second squared, and spins about the centre`() {
        val plan = buildShatterPlan(240f, 330f, "ct")
        val s = plan.shards[3]
        assertEquals(2.4f * 330f, plan.shardGravityPxPerS2, 1e-3f)
        assertEquals(1.2f * 330f, plan.sparkGravityPxPerS2, 1e-3f)
        assertEquals(0f, s.travelX(0f), 0f)
        assertEquals(0f, s.travelY(0f, plan.shardGravityPxPerS2), 0f)
        assertEquals(s.dirX * s.speedPxPerS * 0.2f, s.travelX(0.2f), 1e-3f)
        val fall = s.travelY(0.2f, plan.shardGravityPxPerS2) - s.dirY * s.speedPxPerS * 0.2f
        assertEquals(0.5f * plan.shardGravityPxPerS2 * 0.2f * 0.2f, fall, 1e-3f)
        assertEquals(s.spinRadPerS * 0.2f * (180f / Math.PI.toFloat()), s.turnDegrees(0.2f), 1e-2f)
    }

    @Test
    fun `the strike is a half sine flash, cracks that fade in over 30 ms, and nothing moves yet`() {
        assertEquals(0f, ShatterTimeline.flashAlpha(0f), 0f)
        assertEquals(0.5f, ShatterTimeline.flashAlpha(45f), 1e-6f)
        assertEquals(0f, ShatterTimeline.flashAlpha(90f), 0f)
        assertEquals(0f, ShatterTimeline.flashAlpha(200f), 0f)
        assertTrue(ShatterTimeline.flashAlpha(20f) < ShatterTimeline.flashAlpha(45f))
        assertEquals(0f, ShatterTimeline.crackAlpha(10f, 10f), 0f)
        assertEquals(0.85f * 0.5f, ShatterTimeline.crackAlpha(25f, 10f), 1e-6f)
        assertEquals(0.85f, ShatterTimeline.crackAlpha(40f, 10f), 1e-6f)
        assertEquals(0.85f, ShatterTimeline.crackAlpha(90f, 60f), 1e-6f)
        val plan = buildShatterPlan(240f, 330f, "ct")
        for (s in plan.shards) assertEquals(0f, ShatterTimeline.shardSeconds(89f, s.delayMs), 0f)
    }

    @Test
    fun `the shards fade on one minus the square, from 260 to 560, and the edge glow is gone by 400`() {
        assertEquals(1f, ShatterTimeline.shardAlpha(90f), 0f)
        assertEquals(1f, ShatterTimeline.shardAlpha(260f), 0f)
        assertEquals(1f - 0.25f, ShatterTimeline.shardAlpha(410f), 1e-6f)
        assertEquals(0f, ShatterTimeline.shardAlpha(560f), 0f)
        assertEquals(0.6f, ShatterTimeline.edgeGlowAlpha(90f), 1e-6f)
        assertEquals(0.3f, ShatterTimeline.edgeGlowAlpha(90f + (400f - 90f) / 2f), 1e-6f)
        assertEquals(0f, ShatterTimeline.edgeGlowAlpha(400f), 0f)
        assertEquals(0f, ShatterTimeline.edgeGlowAlpha(560f), 0f)
    }

    @Test
    fun `sparks live from the end of the strike and fade linearly, and none outlives the overlay`() {
        assertEquals(0f, ShatterTimeline.sparkAgeMs(90f), 0f)
        assertEquals(1f, ShatterTimeline.sparkAlpha(0f, 300f), 0f)
        assertEquals(0.5f, ShatterTimeline.sparkAlpha(150f, 300f), 1e-6f)
        assertEquals(0f, ShatterTimeline.sparkAlpha(301f, 300f), 0f)
        assertEquals(0f, ShatterTimeline.sparkAlpha(-1f, 300f), 0f)
        val plan = buildShatterPlan(240f, 330f, "ct")
        for (p in plan.sparks) {
            assertTrue("a spark outlives the overlay", MimirShatterMotion.StrikeMs + p.lifeMs <= MimirShatterMotion.EndMs)
        }
        val p = plan.sparks[0]
        assertEquals(p.vx * 0.1f, ShatterTimeline.sparkOffsetX(p, 100f), 1e-3f)
        assertEquals(p.vy * 0.1f + 0.5f * plan.sparkGravityPxPerS2 * 0.01f, ShatterTimeline.sparkOffsetY(p, 100f, plan.sparkGravityPxPerS2), 1e-3f)
    }

    @Test
    fun `the phases are the strike, the shards and done at the overlay's end`() {
        assertEquals(ShatterPhase.Strike, ShatterTimeline.phaseAt(0f))
        assertEquals(ShatterPhase.Strike, ShatterTimeline.phaseAt(89.9f))
        assertEquals(ShatterPhase.Shards, ShatterTimeline.phaseAt(90f))
        assertEquals(ShatterPhase.Shards, ShatterTimeline.phaseAt(559.9f))
        assertEquals(ShatterPhase.Done, ShatterTimeline.phaseAt(560f))
        assertEquals(ShatterPhase.Done, ShatterTimeline.phaseAt(600f))
    }

    @Test
    fun `the tokens are the spec's clocks, derived from the house clocks where honest`() {
        assertEquals(90, MimirShatterMotion.StrikeMs)
        assertEquals(MimirMotion.OverlayMs / 2, MimirShatterMotion.StrikeMs)
        assertEquals(30, MimirShatterMotion.CrackFadeMs)
        assertEquals(60, MimirShatterMotion.CrackSpreadMs)
        assertEquals(70, MimirShatterMotion.ShardDelaySpreadMs)
        assertEquals(260, MimirShatterMotion.ShardFadeFromMs)
        assertEquals(400, MimirShatterMotion.EdgeGlowEndMs)
        assertEquals(560, MimirShatterMotion.EndMs)
        assertEquals(300, MimirShatterMotion.HeroHoldMs)
        assertEquals(MimirMotion.OverlayMs, MimirShatterMotion.HeroCrossFadeMs)
        assertEquals(300, MimirShatterMotion.GapLeadMs)
        assertEquals(360, MimirShatterMotion.GapGlideMs)
        assertEquals(MimirMotion.OverlayMs * 2, MimirShatterMotion.GapGlideMs)
        assertEquals(560, MimirShatterMotion.ReceiptAtMs)
        assertEquals(660, MimirShatterMotion.InputHoldMs)
        assertEquals(130, MimirMotion.OverlayDissolveCoverMs)
    }

    @Test
    fun `the schedule holds the pad until the gap has closed at 660 and says the receipt at 560`() {
        val s = shatterSchedule(reducedMotion = false)
        assertTrue(s.playsOverlay)
        assertEquals(130, s.startAfterPromptMs)
        assertEquals(560, s.overlayMs)
        assertEquals(300, s.heroHoldMs)
        assertEquals(180, s.heroCrossFadeMs)
        assertEquals(300, s.gapLeadMs)
        assertEquals(360, s.gapGlideMs)
        assertEquals(660, s.gapClosedAtMs)
        assertEquals(660, s.inputHoldMs)
        assertEquals("the pad is released the moment the gap has closed", s.gapClosedAtMs, s.inputHoldMs)
        assertEquals(560, s.receiptAtMs)
        assertTrue("the hero is released before the gap closes", s.heroHoldMs + s.heroCrossFadeMs <= s.gapClosedAtMs)
        assertTrue("the receipt comes after the last shard, before the pad is free", s.receiptAtMs <= s.inputHoldMs)
    }

    @Test
    fun `reduced motion is a cut with the receipt, no overlay, no gap, no hero hold, no held pad, the receipt now`() {
        val s = shatterSchedule(reducedMotion = true)
        assertFalse(s.playsOverlay)
        assertEquals(0, s.startAfterPromptMs)
        assertEquals(0, s.overlayMs)
        assertEquals(0, s.heroHoldMs)
        assertEquals(0, s.heroCrossFadeMs)
        assertEquals(0, s.gapLeadMs)
        assertEquals(0, s.gapGlideMs)
        assertEquals(0, s.gapClosedAtMs)
        assertEquals(0, s.inputHoldMs)
        assertEquals("the receipt still shows, at once", 0, s.receiptAtMs)
        assertNotEquals(shatterSchedule(reducedMotion = false), s)
    }
}
