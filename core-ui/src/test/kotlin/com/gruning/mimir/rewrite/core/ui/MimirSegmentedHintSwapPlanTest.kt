package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The swap's order, without a screen: for every change the Tome footer makes (and a change caught mid-swap) the poses of every piece are stepped from the first
 * frame to the last, in the order the composable runs them (leave, then glide, then arrive), and at EVERY step the words both lines share are at full alpha and
 * no two pieces that show stand over one another. That is the Thor video: "A OPEN · A OPEN · X UNPI · B BACK", "A OPEN · B BACK · B BACK",
 * "X UNPIN" over "Y EDIT".
 */
class MimirSegmentedHintSwapPlanTest {

    private val homeLine = "A OPEN · B BACK"
    private val pinnedLine = "A OPEN · X UNPIN · Y EDIT · B BACK"
    private val unpinnedLine = "A OPEN · X PIN · Y EDIT · B BACK"
    private val createLine = "A CREATE · B BACK"

    /** Whole-pixel widths of a word, and the separator that leads every piece after the first. */
    private val wordWidths = mapOf(
        "A OPEN" to 61, "B BACK" to 58, "X UNPIN" to 74, "X PIN" to 52, "Y EDIT" to 55, "Y CREATE" to 78, "A CREATE" to 80,
    )
    private val separatorWidth = 17
    private val space = 400

    private fun widthOf(unit: MimirHintUnit): Int = wordWidths.getValue(unit.words) + if (unit.separated) separatorWidth else 0

    /** The line at rest: every piece at its centred x, at full alpha. */
    private fun atRest(line: String): Map<MimirHintUnit, MimirHintPose> {
        val units = mimirHintUnits(line)
        val xs = mimirHintCentredXs(units.map(::widthOf), space)
        return units.zip(xs).associate { (unit, x) -> unit to MimirHintPose(x, 1f) }
    }

    /** Every pose of every piece, frame by frame, as the composable steps a change from [from] to [to]. */
    private fun frames(from: Map<MimirHintUnit, MimirHintPose>, to: String, stepsPerPhase: Int = 24): List<Map<MimirHintUnit, MimirHintPose>> {
        val target = mimirHintUnits(to)
        val toXs = target.zip(mimirHintCentredXs(target.map(::widthOf), space)).toMap()
        val plan = mimirHintPlan(from, target)
        val out = ArrayList<Map<MimirHintUnit, MimirHintPose>>()
        fun pose(leave: Float, glide: Float, arrive: Float): Map<MimirHintUnit, MimirHintPose> {
            val phases = MimirHintPhases(leave, glide, arrive)
            val shown = LinkedHashMap<MimirHintUnit, MimirHintPose>()
            (from.keys + target).distinct().forEach { unit ->
                mimirHintPose(from[unit], toXs[unit], phases)?.let { shown[unit] = it }
            }
            return shown
        }
        var leave = if (plan.leaves) 0f else 1f
        var glide = if (plan.glides) 0f else 1f
        var arrive = if (plan.arrives) 0f else 1f
        out += pose(leave, glide, arrive)
        if (plan.leaves) for (i in 1..stepsPerPhase) { leave = i / stepsPerPhase.toFloat(); out += pose(leave, glide, arrive) }
        if (plan.glides) for (i in 1..stepsPerPhase) { glide = i / stepsPerPhase.toFloat(); out += pose(leave, glide, arrive) }
        if (plan.arrives) for (i in 1..stepsPerPhase) { arrive = i / stepsPerPhase.toFloat(); out += pose(leave, glide, arrive) }
        return out
    }

    private fun assertWholeSwap(label: String, from: Map<MimirHintUnit, MimirHintPose>, to: String) {
        val target = mimirHintUnits(to)
        val shared = target.filter { it in from }
        frames(from, to).forEachIndexed { index, frame ->
            shared.forEach { unit ->
                val alpha = frame.getValue(unit).alpha
                // A piece caught mid-fade by an earlier change rises with the arrivals; one that was whole never leaves full alpha.
                if (from.getValue(unit).alpha == 1f) assertEquals("$label step $index: '${unit.words}' stays whole", 1f, alpha, 0f)
            }
            val showing = frame.filterValues { it.alpha > 0f }.entries.sortedBy { it.value.x }
            showing.zipWithNext { left, right ->
                val leftEnd = left.value.x + widthOf(left.key)
                assertTrue(
                    "$label step $index: '${left.key.words}' (to $leftEnd) and '${right.key.words}' (from ${right.value.x}) do not overlap",
                    leftEnd <= right.value.x + 1e-3f,
                )
            }
        }
        // The last step is the new line at rest.
        val last = frames(from, to).last().filterValues { it.alpha > 0f }
        assertEquals("$label: only the new line is left", target.toSet(), last.keys)
        assertEquals("$label: and it stands at rest", atRest(to).mapValues { it.value.x }, last.mapValues { it.value.x })
        last.values.forEach { assertEquals(1f, it.alpha, 0f) }
    }

    @Test
    fun `a line is its words split on the dot, each piece after the first leading with it`() {
        assertEquals(
            listOf(MimirHintUnit("A OPEN", false), MimirHintUnit("X UNPIN", true), MimirHintUnit("Y EDIT", true), MimirHintUnit("B BACK", true)),
            mimirHintUnits(pinnedLine),
        )
        assertEquals(listOf(MimirHintUnit("A OPEN", false), MimirHintUnit("B BACK", true)), mimirHintUnits(homeLine))
        assertEquals(emptyList<MimirHintUnit>(), mimirHintUnits(""))
        assertEquals(listOf(MimirHintUnit("B BACK", false)), mimirHintUnits("· B BACK"))
    }

    @Test
    fun `the Home Shelf line to a custom tome's line and back never doubles or fades a shared word, or overprints`() {
        assertWholeSwap("home to custom", atRest(homeLine), pinnedLine)
        assertWholeSwap("custom to home", atRest(pinnedLine), homeLine)
    }

    @Test
    fun `a pin that changes the word beside the shared ones leaves, then glides, then arrives`() {
        assertWholeSwap("unpin to pin", atRest(pinnedLine), unpinnedLine)
        assertWholeSwap("pin to unpin", atRest(unpinnedLine), pinnedLine)
        val plan = mimirHintPlan(atRest(pinnedLine), mimirHintUnits(unpinnedLine))
        assertEquals(MimirHintPlan(leaves = true, glides = true, arrives = true), plan)
    }

    @Test
    fun `the create tile's line shares only the back word`() {
        assertWholeSwap("custom to create", atRest(unpinnedLine), createLine)
        assertWholeSwap("create to home", atRest(createLine), homeLine)
    }

    @Test
    fun `an addition glides first and then arrives, a removal leaves first and then glides, and an unchanged line does nothing`() {
        assertEquals(
            MimirHintPlan(leaves = false, glides = true, arrives = true),
            mimirHintPlan(atRest(homeLine), mimirHintUnits(pinnedLine)),
        )
        assertEquals(
            MimirHintPlan(leaves = true, glides = true, arrives = false),
            mimirHintPlan(atRest(pinnedLine), mimirHintUnits(homeLine)),
        )
        assertFalse(mimirHintPlan(atRest(homeLine), mimirHintUnits(homeLine)).any)
    }

    @Test
    fun `a change caught mid-swap leaves from the poses on screen and still never doubles or overprints`() {
        // Caught in the middle of the glide to the custom line, then sent back to Home, and on to Create.
        val caughtGliding = frames(atRest(homeLine), pinnedLine)[10]
        assertWholeSwap("gliding, then home", caughtGliding.filterValues { it.alpha > 0f }, homeLine)
        val caughtLeaving = frames(atRest(pinnedLine), homeLine)[10]
        assertWholeSwap("leaving, then create", caughtLeaving.filterValues { it.alpha > 0f }, createLine)
        val caughtArriving = frames(atRest(homeLine), pinnedLine)[24 + 10]
        assertWholeSwap("arriving, then home", caughtArriving.filterValues { it.alpha > 0f }, homeLine)
    }

    @Test
    fun `a piece in both lines glides at the alpha it had, one only in the old fades from where it stood, one only in the new fades in where it will stand`() {
        val shared = MimirHintPose(x = 10f, alpha = 1f)
        val phases = MimirHintPhases(leave = 0.5f, glide = 0.25f, arrive = 0.75f)
        assertEquals(MimirHintPose(x = 15f, alpha = 1f), mimirHintPose(shared, 30f, phases)!!.copy(x = 15f))
        assertEquals(10f + 20f * 0.25f, mimirHintPose(shared, 30f, phases)!!.x, 1e-4f)
        assertEquals(1f, mimirHintPose(shared, 30f, phases)!!.alpha, 0f)
        assertEquals(MimirHintPose(x = 10f, alpha = 0.5f), mimirHintPose(shared, null, phases))
        assertEquals(MimirHintPose(x = 30f, alpha = 0.75f), mimirHintPose(null, 30f, phases))
        assertNull(mimirHintPose(null, null, phases))
    }

    @Test
    fun `the line is centred, never left of its start, and the widths are the pieces' own`() {
        assertEquals(listOf(100f, 200f), mimirHintCentredXs(listOf(100, 100), 400))
        assertEquals(listOf(0f, 300f), mimirHintCentredXs(listOf(300, 300), 400))
    }

    @Test
    fun `a start-aligned line lays its pieces end to end from x 0`() {
        assertEquals(listOf(0f, 100f, 250f), mimirHintStartXs(listOf(100, 150, 50)))
        assertEquals(emptyList<Float>(), mimirHintStartXs(emptyList()))
    }
}
