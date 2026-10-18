package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isNotSelected
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * the dock seat you can SEE is the dock seat that ACTS.
 *
 * ## The report this exists for
 *
 * Three reports from a Retroid Pocket Nova showed the Library rune activating Utility. Two mechanisms produce that symptom and they have completely
 * different fixes:
 *
 *  1. **A routing defect** — the LIBRARY seat's activation lambda is wired to the wrong action, or the
 *     whole row is offset by one seat.
 *  2. **A hit-target defect** — the LIBRARY rune is PAINTED over UTILITY's touch bounds, so the pixels the
 *     player aims at and the bounds that answer the tap belong to different seats.
 *
 * Nothing in the tree could tell those apart when that symptom was reported. `MimirRunicQuickRailTouchGrammarTest`
 * pins the *grammar* of the tap (one tap, one `onActivate`, never `onRequestFocus`) as bounded source
 * text; `MimirFinalShelfPrimitivesTest` pins the slot ARITHMETIC. Neither renders the rail, so neither can
 * observe that the painted coin and the clickable slot are the same seat. This file closes that gap by
 * clicking the PAINTED COIN — not the slot, not a computed coordinate — and asserting which action fires.
 *
 * ## Why the Nova and not the Odin
 *
 * The Odin (832.5dp) takes [mimirQuickRailSlotWidths]'s unscaled early return: six authored 88dp seats,
 * device-verified 2026-08-22 on Odin `test device` against installed frontend
 * `08bd06abfe37a9cc…` — accessibility bounds `[351,857][554,1055]` … `[1366,857][1569,1055]`, six
 * contiguous 203px bands whose centres sit exactly under the painted runes, and all six seats routed
 * correctly on touch AND controller. **The Nova (640dp) is a different branch of the same function**: its
 * rail narrows to 368dp and every seat to 61.33dp, and that branch was rewritten wholesale by the
 * 2026-08-21 equal-seat override ([MimirRunicQuickRailDefaults.ActionSlotWidth]). A gate that only
 * exercised the Odin would certify the branch that was NOT touched. Every certified viewport is therefore
 * a case below, and the two that matter most — 640dp (scaled) and 320dp (the per-seat floor) — are the
 * ones the Odin can never reach.
 *
 * ## What each assertion can actually catch
 *
 * - [seatBandsAreContiguousAndInEnumOrder] catches mechanism 1's whole-row form: a rail whose seats are
 *   permuted, overlapping, or gapped. A row offset by one seat cannot survive it.
 * - [paintedCoinSitsInsideItsOwnSeatBand] catches mechanism 2 directly: the coin's painted centre must
 *   fall strictly inside the touch band that its own label answers for.
 * - [clickingThePaintedCoinActivatesThatSeat] is the observed symptom as an executable case —
 *   click the rune, assert the action. It is the only one of the three that would still be true if the
 *   other two were deleted, and the only one that spans BOTH mechanisms at once.
 *
 * **What it cannot see**, stated so nobody reads a green run as more than it is: this renders the rail
 * alone. It cannot see a foreign composable drawn OVER the dock stealing taps (that is a Home-surface
 * question, `feature-library`), it cannot see the controller dispatch (that is `HomeShelfModels.kt`'s
 * reducer, pinned separately), and Robolectric's layout is not a device rasterizer. It answers exactly
 * one question — within the rail, does the seat you see act as itself — at every width the product ships.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h1024dp-mdpi")
class MimirRunicQuickRailSeatIdentityTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Every width the product is certified at, plus the two branch boundaries. `Odin` and `RP5` clear the
     * authored 528dp and take the unscaled path; `Nova`, `538 companion` and `320 floor` take the scaled
     * path — `320 floor` is the only one that reaches [MimirRunicQuickRailDefaults.MinActionDiameter].
     * Named rather than a bare list so a failure says WHICH device is broken.
     */
    private val viewports = listOf(
        "Odin 832.5dp" to 832.5.dp,
        "RP5-class 700dp" to 700.dp,
        "Retroid Pocket Nova 640dp" to 640.dp,
        "538dp companion" to 538.dp,
        "320dp minimum floor" to 320.dp,
    )

    @Test
    fun `seat bands are contiguous and in enum order at every certified viewport`() {
        installRail()
        viewports.forEach { (name, width) ->
            val bands = measureSeatBandsAt(width)

            MimirQuickRailAction.entries.forEachIndexed { index, action ->
                val band = bands.getValue(action)
                assertTrue(
                    "$name: ${action.name}'s touch band is empty or inverted ($band)",
                    band.second > band.first,
                )
                if (index > 0) {
                    val previous = bands.getValue(MimirQuickRailAction.entries[index - 1])
                    // Contiguous AND ordered: the seat to the right of a seat must START where its
                    // neighbour ENDED. This is the assertion a whole-row-offset-by-one dies on, and it
                    // also refuses an overlap (which is how one seat would answer another's taps).
                    assertTrue(
                        "$name: ${action.name} must begin exactly where " +
                            "${MimirQuickRailAction.entries[index - 1].name} ends — " +
                            "previous=$previous this=$band",
                        kotlin.math.abs(band.first.value - previous.second.value) < 0.51f,
                    )
                }
            }
        }
    }

    @Test
    fun `the painted coin sits inside its own seat's touch band at every certified viewport`() {
        installRail()
        viewports.forEach { (name, width) ->
            val bands = measureSeatBandsAt(width)
            val coins = MimirQuickRailAction.entries.associateWith { action ->
                compose.onNodeWithTag(coinTag(action), useUnmergedTree = true).horizontalExtent()
            }

            MimirQuickRailAction.entries.forEach { action ->
                val (bandLeft, bandRight) = bands.getValue(action)
                val (coinLeft, coinRight) = coins.getValue(action)
                val coinCentre = (coinLeft.value + coinRight.value) / 2f
                assertTrue(
                    "$name: the ${action.name} rune is painted at ${coinCentre}dp, which is OUTSIDE the " +
                        "touch band that answers for '${quickRailLabel(action)}' " +
                        "(${bandLeft.value}..${bandRight.value}dp). This is RC-52's hit-target mechanism: " +
                        "the player aims at one seat and a different seat answers.",
                    coinCentre > bandLeft.value && coinCentre < bandRight.value,
                )
                // The coin must not merely be centred in the right band — it must fit inside it, or its
                // edge overhangs a neighbour and the two seats' visible circles collide.
                assertTrue(
                    "$name: the ${action.name} coin (${coinLeft.value}..${coinRight.value}dp) overhangs " +
                        "its own ${bandLeft.value}..${bandRight.value}dp seat",
                    coinLeft.value >= bandLeft.value - 0.51f && coinRight.value <= bandRight.value + 0.51f,
                )
            }
        }
    }

    @Test
    fun `clicking the painted coin activates that seat and no other`() {
        installRail()
        viewports.forEach { (name, width) ->
            resizeTo(width)
            MimirQuickRailAction.entries.forEach { action ->
                activations.clear()

                compose.onNodeWithTag(coinTag(action), useUnmergedTree = true).performClick()
                compose.waitForIdle()

                assertEquals(
                    "$name: clicking the PAINTED ${action.name} rune must activate ${action.name} " +
                        "exactly once. RC-52's report is this assertion failing with " +
                        "LIBRARY -> BRIGHTNESS at 640dp.",
                    listOf(action),
                    activations.toList(),
                )
            }
        }
    }

    @Test
    fun `clicking a seat's own label activates that seat at every certified viewport`() {
        installRail()
        viewports.forEach { (name, width) ->
            resizeTo(width)
            MimirQuickRailAction.entries.forEach { action ->
                activations.clear()

                compose.onNodeWithContentDescription(quickRailLabel(action)).performClick()
                compose.waitForIdle()

                assertEquals(
                    "$name: activating the node labelled '${quickRailLabel(action)}' must dispatch ${action.name}",
                    listOf(action),
                    activations.toList(),
                )
            }
        }
    }

    /**
     * The LABELLED node must be the ACTIONABLE node, and it must carry the destination state.
     *
     * Device finding 2026-08-22, Odin `test device`, frontend `08bd06abfe37a9cc…`: each dock seat
     * emitted **three** accessibility nodes at identical bounds `[351,857][554,1055]` — one
     * `clickable=true focusable=true` with NO label, one labelled `"Home"` with `clickable=false` and
     * `selected=false`, and one bare `android.widget.Button`. So a screen reader (or any automation driver)
     * that resolves a seat by its label lands on a node with no click action and no selected state, while
     * the node that would answer a click has no name. `MimirActivatable.kt:42-48` documents this exact
     * failure mode as the one a hurried migration drops.
     */
    @Test
    fun `each seat's labelled node carries its own click action and destination state`() {
        installRail()
        viewports.forEach { (name, width) ->
            resizeTo(width)
            MimirQuickRailAction.entries.forEach { action ->
                compose.onNodeWithContentDescription(quickRailLabel(action)).assert(hasClickAction())
            }
            // `selectedAction = HOME` in [installRail], mirroring HomeShelfScreen.kt's own Home rail.
            compose.onNodeWithContentDescription(quickRailLabel(MimirQuickRailAction.HOME))
                .assert(isSelected())
            MimirQuickRailAction.entries
                .filter { it != MimirQuickRailAction.HOME }
                .forEach { action ->
                    compose.onNodeWithContentDescription(quickRailLabel(action)).assert(isNotSelected())
                }
            assertTrue("$name: viewport applied", width.value > 0f)
        }
    }

    /** The solved rail must be exactly what [mimirQuickRailSlotWidths] says it is — no silent overflow. */
    @Test
    fun `rendered seat widths match the slot solver at every certified viewport`() {
        installRail()
        viewports.forEach { (name, width) ->
            val bands = measureSeatBandsAt(width)
            val solved = mimirQuickRailSlotWidths(
                MimirQuickRailAction.entries,
                mimirQuickRailTargetWidth(width),
            )

            MimirQuickRailAction.entries.forEachIndexed { index, action ->
                val (left, right) = bands.getValue(action)
                assertEquals(
                    "$name: ${action.name}'s RENDERED seat width must equal the solver's",
                    solved[index].value.toDouble(),
                    (right.value - left.value).toDouble(),
                    0.51,
                )
            }
        }
    }

    // ---- harness -------------------------------------------------------------------------------

    private fun coinTag(action: MimirQuickRailAction) = "rc52/coin/${action.name}"

    /** Every activation the rail dispatched since the last [MutableList.clear]. */
    private val activations = mutableListOf<MimirQuickRailAction>()

    /**
     * The rail's available width. Compose's test rule permits exactly ONE `setContent` per test, so the
     * viewport is snapshot state the rail RE-LAYS-OUT against rather than a fresh composition per case —
     * which is also the more honest instrument: it is the same code path a real device takes through a
     * configuration change, not six independent cold starts.
     */
    private val railWidth = mutableStateOf(viewports.first().second)

    /**
     * Installs the rail alone. The coin tag rides in through [MimirRunicQuickRail]'s `actionModifier` —
     * the SAME seam a feature uses to attach `mimirFocusable`, applied to the same inner Box that paints
     * the visible circle. That is deliberate: it means the node this test clicks is literally the painted
     * coin, not a coordinate recomputed from the same arithmetic the production code used (which would
     * agree with itself no matter how wrong it was).
     */
    private fun installRail() {
        compose.setContent {
            MimirTheme {
                Box(Modifier.width(railWidth.value).height(120.dp)) {
                    MimirRunicQuickRail(
                        selectedAction = MimirQuickRailAction.HOME,
                        focusedAction = null,
                        onActionFocus = { },
                        onActionActivate = activations::add,
                        actionModifier = { action -> Modifier.testTag(coinTag(action)) },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun resizeTo(width: Dp) {
        compose.runOnIdle { railWidth.value = width }
        compose.waitForIdle()
        activations.clear()
    }

    private fun measureSeatBandsAt(width: Dp): Map<MimirQuickRailAction, Pair<Dp, Dp>> {
        resizeTo(width)
        return MimirQuickRailAction.entries.associateWith { action ->
            compose.onNodeWithContentDescription(quickRailLabel(action)).horizontalExtent()
        }
    }

    private fun SemanticsNodeInteraction.horizontalExtent(): Pair<Dp, Dp> =
        getUnclippedBoundsInRoot().let { it.left to it.right }
}
