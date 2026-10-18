package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **T-1 — the touch grammar of Home's shelf and gamelist.**
 *
 * What was wrong: sixteen sites tree-wide carried `if (focused) onActivate() else requestFocus(key)`, an
 * idiom inherited from the retired WebView product where pointer and key dispatch could not be separated.
 * Compose separates them. Touch is **activate-at-a-point** — one event, focus is a *consequence*;
 * a controller is **focus-then-activate** — two events, by nature. Two grammars, one set of actions.
 * Four of those sites sat on the captured navigation path and compounded: two taps to open a Tome seat, then two
 * more on Play.
 *
 * This module runs plain JUnit with no Compose harness (`feature-library`'s Compose tests live in
 * `androidTest`, which needs a device). So the split is deliberate:
 *  - the DECISIONS are pure functions, pinned here;
 *  - the WIRING — that the composables actually call those decisions and no longer carry the old idiom —
 *    is pinned here as comment-stripped source text;
 *  - the BEHAVIOUR under real injected touch is pinned in `app`'s Robolectric Compose harness
 *    (`HomeLaunchCardTouchGrammarTest`, `HomeTomeFolioTouchGrammarTest`), which is where a tap can
 *    actually be delivered on the JVM.
 *
 * The source pins strip comments first. Every one of these files now *documents* the rejected idiom in
 * prose — a comment-blind `contains` check would read its own explanation as the defect and fail green
 * code, or worse, pass on a file where the idiom survives only in a comment.
 */
class HomeTouchGrammarTest {

    // ── The decisions ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a Tome seat tap opens on the first tap in BROWSE`() {
        assertEquals(
            HomeTomeFolioMemberTapAction.FOCUS_AND_OPEN,
            homeTomeFolioMemberTapAction(HomeTomeFolioMode.BROWSE, arrangeSettledHere = false),
        )
        assertEquals(
            "settlement is irrelevant in BROWSE -- a tap already names its target by the pixel it hit",
            HomeTomeFolioMemberTapAction.FOCUS_AND_OPEN,
            homeTomeFolioMemberTapAction(HomeTomeFolioMode.BROWSE, arrangeSettledHere = true),
        )
    }

    @Test
    fun `ARRANGE keeps its exact-target step and the asymmetry is declared`() {
        assertEquals(
            "Arrange's touch grammar is press-and-hold carry and a lift/drop is a reducer transaction: " +
                "an unsettled seat must only take focus",
            HomeTomeFolioMemberTapAction.FOCUS_ONLY,
            homeTomeFolioMemberTapAction(HomeTomeFolioMode.ARRANGE, arrangeSettledHere = false),
        )
        assertEquals(
            HomeTomeFolioMemberTapAction.FOCUS_AND_ARRANGE_CONFIRM,
            homeTomeFolioMemberTapAction(HomeTomeFolioMode.ARRANGE, arrangeSettledHere = true),
        )
    }

    @Test
    fun `a launch card action publishes focus BEFORE it acts`() {
        val authority = DefaultFocusAuthority()
        authority.pushScope(HomeLaunchCardScope)
        val play = HomeLaunchCardPlayKey
        val achievements = HomeLaunchCardAchievementsKey
        authority.register(play, HomeLaunchCardScope, FocusBounds(0f, 0f, 10f, 10f))
        authority.register(achievements, HomeLaunchCardScope, FocusBounds(20f, 0f, 30f, 10f))
        authority.requestFocus(achievements)

        var focusedWhenActed: FocusKey? = null
        activateHomeLaunchCardAction(authority, play) {
            focusedWhenActed = authority.state.value.focused
        }

        // Load-bearing ordering, not cosmetics: Home binds `onPlay` to `dispatch(CONFIRM)`, which
        // resolves its target from the CURRENTLY FOCUSED key. If the action ran first, a tap on Play
        // while Achievements held focus would run ACHIEVEMENTS.
        assertEquals(
            "the action must observe its own key already focused",
            play,
            focusedWhenActed,
        )
        assertEquals(play, authority.state.value.focused)
    }

    @Test
    fun `an unregistered action still runs, so a tap is never silently eaten`() {
        val authority = DefaultFocusAuthority()
        authority.pushScope(HomeLaunchCardScope)
        var ran = 0

        // requestFocus is a no-op for a key that has not registered yet (a mid-mount frame).
        // The activation must not be conditional on it having worked.
        activateHomeLaunchCardAction(authority, HomeLaunchCardPlayKey) { ran++ }

        assertEquals("the tap still acts", 1, ran)
        assertNull(authority.state.value.focused)
    }

    // ── The wiring ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the Tome seat consults the tap decision and no longer gates on settlement`() {
        // Bounded by the seat lambda's own drag-suppression guard and by the branch that opens the
        // member: the exact region where the decision is made, so the pin cannot pass on a slice that
        // accidentally spans half the file.
        val block = code("HomeTomeFolio.kt").sliceBetween(
            after = "if (SystemClock.uptimeMillis() < suppressActivationUntilMillis) {",
            before = "onOpenMember(member)",
        )
        assertTrue(
            "the seat must route through homeTomeFolioMemberTapAction, so the BROWSE/ARRANGE split " +
                "lives in one pinned pure function instead of being re-derived inline. Found: <$block>",
            block.contains("homeTomeFolioMemberTapAction("),
        )
        assertFalse(
            "the old exact-target gate must be GONE, not merely bypassed -- a surviving `settledHere` " +
                "is how the two-tap comes back the next time someone edits this lambda. Found: <$block>",
            block.contains("settledHere"),
        )
        assertTrue(
            "and the whole file must be free of it, so no sibling seat keeps the idiom alive",
            !code("HomeTomeFolio.kt").contains("settledHere"),
        )
    }

    @Test
    fun `no launch-card action spends a tap on focus`() {
        val source = code("HomeLaunchCard.kt")
        assertFalse(
            "the focus-gated two-tap idiom must not survive anywhere in the launch card. Found in: " +
                "<${source.lines().filter { it.contains("if (focused) onActivate()") }}>",
            source.contains("if (focused) onActivate()"),
        )
        assertTrue(
            "every action tap goes through the one documented helper",
            source.contains("activateHomeLaunchCardAction(authority, key, onActivate)"),
        )
    }

    @Test
    fun `the cover art is a tap target that performs Play`() {
        val block = code("HomeLaunchCard.kt").sliceBetween(
            after = "private fun HomeLaunchCardFreeArt(",
            before = "private fun HomeLaunchCardFooter(",
        )
        assertTrue(
            "the art needs an actual pointer node. Found: <$block>",
            block.contains("detectTapGestures(onTap = { currentTapPlay() })"),
        )
        assertTrue(
            "and it must be findable by the Compose harness",
            block.contains("testTag(HomeLaunchCardCoverArtTestTag)"),
        )
        val wiring = code("HomeLaunchCard.kt")
        assertTrue(
            "the art's tap must perform PLAY's action through the same helper the Play button uses, " +
                "so the two can never drift into two different meanings of `launch`",
            wiring.contains("activateHomeLaunchCardAction(authority, focusContract.playKey, onPlay)"),
        )
    }

    /**
     * The hazard `clickable` carries is invisible to `tools/mimir_ui_invariants_gate.py`, whose F1 rules
     * grep only for the literals `.focusable(` and `focusTarget(`. Both `clickable` and
     * `combinedClickable` build an `AbstractClickableNode`, which constructs a `FocusableNode` and
     * therefore a real `FocusTargetModifierNode` — a second Compose focus target beside the single
     * FocusAuthority. `GamelistCarousel.kt` already states this in its own comment. So the pin lives
     * here, scoped to the surfaces converted here.
     */
    @Test
    fun `the converted tap targets use a pointer gesture, never clickable`() {
        // Content-anchored, not brace-matched: the slice runs from the button's declaration to the
        // label it renders, i.e. exactly its modifier chain. A brace pattern would silently degrade
        // to the rest of the file the moment the formatting changed.
        val button = code("HomeLaunchCard.kt").sliceBetween(
            after = "private fun HomeLaunchActionButton(",
            before = "MimirChromeText(",
        )
        assertFalse(
            "the action button must not reintroduce a Compose focus node. Found: <$button>",
            button.contains("clickable("),
        )
        assertTrue(button.contains("detectTapGestures("), "expected a pointer gesture in <$button>")

        val cell = code("HomeTomeFolio.kt").sliceBetween(
            after = "if (onActivate == null) {",
            before = "contentAlignment = Alignment.Center,",
        )
        assertFalse(
            "the Tome seat must not reintroduce a Compose focus node. Found: <$cell>",
            cell.contains("clickable("),
        )
        assertTrue(cell.contains("detectTapGestures("), "expected a pointer gesture in <$cell>")
    }

    /**
     * `pointerInput`'s keys are a RESTART contract, not a capture list: a changed key disposes the
     * detector and cancels the gesture in flight, so a key that moves with recomposition eats taps.
     * Both surfaces recompose with a finger down (focus ink animates; folio seats animate through a
     * page travel).
     *
     * **Stated honestly, because it was measured:** keying these nodes on their lambda is *currently*
     * harmless — the lambda-keyed variant still delivered taps in `app`'s Compose harness, because
     * strong skipping (default-on from Compose compiler 2.0.20; this tree is Kotlin 2.0.21) memoizes
     * the literals despite their unstable captures. This pin therefore guards a SHAPE, not a live
     * defect: it keeps the gesture nodes independent of a compiler setting and of whatever capture
     * someone adds to those lambdas next. There is no behavioural differential for it and this test
     * does not pretend otherwise.
     */
    @Test
    fun `the gesture nodes do not depend on lambda identity`() {
        listOf("HomeLaunchCard.kt", "HomeTomeFolio.kt").forEach { name ->
            val source = code(name)
            Regex("""\.pointerInput\(([^)]*)\)""").findAll(source).forEach { match ->
                val keys = match.groupValues[1].trim()
                assertTrue(
                    "$name keys a pointerInput on <$keys>. A pointerInput that a converted tap target " +
                        "owns must be keyed on Unit and read its target via rememberUpdatedState, or a " +
                        "recomposition mid-gesture cancels the tap.",
                    keys == "Unit",
                )
            }
        }
    }

    /**
     * `onDoubleTap` is the OTHER defect (mechanism B): registering it defers EVERY single tap by the
     * platform double-tap timeout, because `detectTapGestures` branches to `awaitSecondDown` when the
     * field is non-null. No Home touch handler may reintroduce it on Home's path.
     */
    @Test
    fun `no double-tap window is registered on the surfaces this lane touched`() {
        listOf("HomeLaunchCard.kt", "HomeTomeFolio.kt", "HomeShelfScreen.kt", "GameShelf.kt", "LibraryGameShelf.kt")
            .forEach { name ->
                assertFalse(
                    "$name must not register onDoubleTap -- it puts a ~300ms deferral on every single tap",
                    code(name).contains("onDoubleTap"),
                )
                assertFalse(
                    "$name must not use combinedClickable -- same AbstractClickableNode focus node as " +
                        "clickable, and its onDoubleClick parameter is the same trap",
                    code(name).contains("combinedClickable"),
                )
            }
    }

    // ── Source-pin plumbing ──────────────────────────────────────────────────────────────────────

    private fun assertTrue(condition: Boolean, message: String) = assertTrue(message, condition)

    /** Presence-checked slice: a missing anchor fails loudly instead of degrading to the whole file. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        val tail = substringAfter(after)
        assertTrue("closing anchor missing after '$after': $before", tail.contains(before))
        return tail.substringBefore(before)
    }

    /**
     * The file with comments removed. Every file pinned here now explains the rejected idiom in prose;
     * without this a `contains` check reads the explanation as the code.
     */
    private fun code(name: String): String = stripKotlinComments(source(name))

    private fun source(name: String): String {
        val candidates = listOf(
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    companion object {
        /**
         * Strips `//` line comments and `/* */` blocks while preserving string literals and, critically,
         * every newline — so a slice anchored on a declaration still lands on the same construct.
         * Deliberately small: it is not a Kotlin lexer, it only has to survive this tree's own comments.
         */
        internal fun stripKotlinComments(text: String): String {
            val out = StringBuilder(text.length)
            var index = 0
            var inString = false
            var inChar = false
            while (index < text.length) {
                val c = text[index]
                val next = text.getOrNull(index + 1)
                when {
                    inString -> {
                        out.append(c)
                        if (c == '\\') {
                            text.getOrNull(index + 1)?.let(out::append)
                            index++
                        } else if (c == '"') {
                            inString = false
                        }
                        index++
                    }
                    inChar -> {
                        out.append(c)
                        if (c == '\\') {
                            text.getOrNull(index + 1)?.let(out::append)
                            index++
                        } else if (c == '\'') {
                            inChar = false
                        }
                        index++
                    }
                    c == '"' -> { inString = true; out.append(c); index++ }
                    c == '\'' -> { inChar = true; out.append(c); index++ }
                    c == '/' && next == '/' -> {
                        while (index < text.length && text[index] != '\n') index++
                    }
                    c == '/' && next == '*' -> {
                        index += 2
                        while (index < text.length && !(text[index] == '*' && text.getOrNull(index + 1) == '/')) {
                            if (text[index] == '\n') out.append('\n')
                            index++
                        }
                        index += 2
                    }
                    else -> { out.append(c); index++ }
                }
            }
            return out.toString()
        }
    }
}
