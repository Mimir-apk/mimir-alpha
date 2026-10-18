package com.gruning.mimir.rewrite.core.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the dock's one-tap touch grammar : a touch tap on ANY rail action,
 * focused or not, must call `onActivate()` exactly once and must never call `onRequestFocus()`. The
 * retired shape was `if (focused) onActivate() else onRequestFocus()`; the rejected NAIVE fix is
 * `onRequestFocus(); onActivate()` unconditionally (which push-then-immediately-pops the LIBRARY/HOME
 * rail scope on a cold tap -- see AppShelfQuickRailTest's `libraryQuickRailActivate` pins). Both
 * mutations are caught here because both, textually, call `onRequestFocus` at all.
 *
 * This is a bounded source-text pin -- house pattern, see `app`'s `MButtonQuickLaunchInPlaceContractTest`.
 * Bounded on the gesture block itself (`.pointerInput(` .. the next `.semantics(`) so a change anywhere
 * else in the file cannot silently satisfy or defeat it, and comment-stripped before the negative/count
 * checks so a comment discussing the retired grammar (there is one, right above the real call) cannot
 * trip it. A source-text pin is the right instrument HERE because the thing being pinned is the SHAPE of
 * the call, not a rendered outcome -- `core-ui/src/test` does now carry a Robolectric Compose harness
 * (`MimirTextMetricsHarnessTest`, and `MimirRunicQuickRailSeatIdentityTest` which renders this same rail
 * and clicks it), so "no Compose harness exists in this module", which this KDoc asserted until
 * 2026-08-22, is no longer true and must not be used to justify staying textual.
 *
 * ## The anchor moved 2026-08-22, and why
 *
 * This used to slice `.clickable(`. RC-52's investigation dumped the dock's real accessibility tree off
 * the Odin and found `Modifier.clickable` emitting a second Compose focus target
 * (`focusable=true`) on every seat plus a name/action split -- see the block comment on
 * `MimirRunicQuickRailItem`'s modifier chain for the measured node shape. The clickable is gone; the
 * grammar it carried is not, so the assertions below are unchanged and only the anchor moved.
 * The `the dock never reintroduces Modifier clickable` case below is what stops it coming back.
 */
class MimirRunicQuickRailTouchGrammarTest {

    @Test
    fun `touch gesture calls onActivate exactly once and never onRequestFocus or focused`() {
        val clickable = gestureBlock().withoutLineComments()

        assertTrue(
            "the retired two-tap grammar must be gone entirely -- onRequestFocus must not appear " +
                "anywhere in the clickable block (this also catches the naive 'call both' collapse, " +
                "which still calls onRequestFocus). Block was:\n$clickable",
            !clickable.contains("onRequestFocus"),
        )
        assertTrue(
            "the clickable body must never branch on `focused` -- it must be an unconditional " +
                "activation, not a reintroduced if/else. Block was:\n$clickable",
            !Regex("""\bfocused\b""").containsMatchIn(clickable),
        )
        val inertObserverPath = clickable.substringAfter("if (currentTouchObserver == null)")
            .substringBefore("} else")
        val observedPath = clickable.substringAfter("} else")
        assertEquals(
            "the release/null observer path must activate exactly once. Block was:\n$clickable",
            1,
            Regex("""currentActivate\(\)""").findAll(inertObserverPath).count(),
        )
        assertEquals(
            "the observed path must activate exactly once. Block was:\n$clickable",
            1,
            Regex("""currentActivate\(\)""").findAll(observedPath).count(),
        )
    }

    /** Source pin for the optional observer seam; coordinate instrumentation is the behavioral proof. */
    @Test
    fun `the existing detector reports press outcome and tap without a second pointer node`() {
        val gesture = gestureBlock().withoutLineComments()

        assertTrue(gesture.contains("onPress ="))
        assertTrue(gesture.contains("tryAwaitRelease()"))
        assertTrue(gesture.contains("currentTouchObserver"))
        assertTrue(gesture.contains("onTap ="))
        assertTrue(gesture.contains("currentActivate()"))
    }

    /**
     * The dock is a controller-navigated surface, so the controller-focus invariant applies to it
     * in full (`core-input/.../MimirActivatable.kt:27-31`): `clickable` beside a `mimirFocusable`
     * registration is the defect, because `AbstractClickableNode` builds a `FocusableNode` and therefore a
     * real second focus target. `tools/mimir_ui_invariants_gate.py`'s F1 rules grep only for the literals
     * `.focusable(` / `focusTarget(`, so neither modifier matches and the drift is silent to it -- which
     * is exactly how it survived here until a device accessibility dump found it. This is the rule's
     * executable form for this one file.
     */
    @Test
    fun `the dock never reintroduces Modifier clickable`() {
        val source = sourceFile("MimirRunicQuickRail.kt").withoutLineComments()

        listOf(".clickable(", ".combinedClickable(", ".focusable(", "focusTarget(").forEach { forbidden ->
            assertTrue(
                "MimirRunicQuickRail.kt must not use `$forbidden`. The dock's seats carry a " +
                    "`mimirFocusable` registration (handed in through `actionModifier`), and every one of " +
                    "these plants a second Compose focus target beside it -- device-measured as " +
                    "`focusable=true` on all six seats before this was removed. Activation is a raw " +
                    "`pointerInput` gesture plus ONE merged semantics block; see the modifier chain's own " +
                    "block comment.",
                !source.contains(forbidden),
            )
        }
    }

    /**
     * The semantics block must stay ONE block carrying all four properties. Split across two blocks, the
     * Android accessibility delegate emitted the name on an inert node and the click action on an
     * anonymous one -- device-measured on the Odin, three nodes per seat at identical bounds.
     */
    @Test
    fun `one merged semantics block carries role name selected and the click action`() {
        val semantics = sourceFile("MimirRunicQuickRail.kt")
            .sliceBetween(".semantics(mergeDescendants = true) {", "horizontalAlignment")
            .withoutLineComments()

        listOf("this.role = Role.Button", "contentDescription = label", "this.selected = selected", "onClick(")
            .forEach { required ->
                assertTrue(
                    "the dock seat's merged semantics block must contain `$required` -- all four in ONE " +
                        "block, or the accessibility node splits again. Block was:\n$semantics",
                    semantics.contains(required),
                )
            }
    }

    private fun gestureBlock(): String {
        val source = sourceFile("MimirRunicQuickRail.kt")
        return source.sliceBetween(".pointerInput(", ".semantics(")
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of degrading to the whole input. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        return substringAfter(after).substringBefore(before)
    }

    private fun String.withoutLineComments(): String =
        lineSequence().joinToString("\n") { it.substringBefore("//") }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
