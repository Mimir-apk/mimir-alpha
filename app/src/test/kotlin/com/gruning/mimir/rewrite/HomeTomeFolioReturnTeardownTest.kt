package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP02 beat 3 : the
 * Folio's teardown in `HomeShelfCoordinator.kt`'s `closeTomeToLibrary` is released by the Library's OWN
 * arrival settling, never by a frame count.
 *
 * **Rewritten post-crash, 2026-09-02.** The first version of this file pinned the wait as living INSIDE
 * `closeTomeToLibrary` itself, fenced by an epoch captured in the same function, released by a
 * `scope.launch` in the same function. That shape shipped a `navigation: MimirNavigationCoordinator`
 * parameter on `rememberHomeShelfCoordinator` (a ~2300-line composable already dense with inlined
 * `remember`/`derivedStateOf` bodies) and VerifyError'd the app on every cold start. The wait now lives in
 * `HomeTomeLibraryReturnReleaseEffect`, a genuinely separate top-level composable in
 * `MimirAppCoordinator.kt`, called with one line from `MimirAppCoordinator`'s own body. This file's pins
 * follow the code to its new location rather than pinning the old shape.
 *
 * No Compose harness exists in this module to stand up the whole host and drive a real coroutine race, so
 * — same discipline `IncomingOverlayReducedMotionThreadingTest` and `HomeTomeLibrarySettleWiringTest` use
 * for this exact class of problem — this file pins the STRUCTURAL properties a regression would violate:
 *
 * 1. The four-frame count is GONE from `closeTomeToLibrary`, not stacked alongside the new hold.
 * 2. `closeTomeToLibrary` no longer references `navigation`/the settle latch at all, and launches no
 *    coroutine -- it only stores the fence its caller computed, or clears immediately when the caller
 *    passes `null` (no incoming arrival to sequence against -- the deleted-tome path).
 * 3. `closeTomeSurface` (`MimirAppInputDispatch.kt`) captures the fence and passes it as an argument.
 * 4. `HomeTomeLibraryReturnReleaseEffect` (`MimirAppCoordinator.kt`) does the actual wait: the epoch
 *    fence AND settled, OR the reversal escape (the browser torn down mid-wait).
 * 5. `completeTomeLibraryReturn` guards on fence equality before clearing the session.
 * 6. The arrival epoch and the settle latch still share exactly one writer.
 */
class HomeTomeFolioReturnTeardownTest {

    // Comments stripped before slicing -- this file's own extensive prose comments (and the production
    // kdocs they read) quote every signal name under discussion in explaining what changed, so an
    // unstripped `.contains(...)` cannot tell "the code does this" apart from "a comment mentions this".
    // Caught by this file's own differential test in the first round: reverting closeTomeToLibrary to the
    // old repeat(4) body left a surrounding comment block narrating homeTomeLibraryOpenSettled in place,
    // and an unstripped check stayed GREEN against clearly-reverted code. Same discipline
    // `HomeTomeLibrarySettleWiringTest.source()` already uses.
    private fun source(relativePath: String): String {
        val file = File(relativePath)
        assertTrue(
            "$relativePath not found at ${file.absolutePath} -- if this file moved, move this pin with it.",
            file.exists(),
        )
        return file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
    }

    private fun homeShelfCoordinator(): String =
        source("src/main/kotlin/com/gruning/mimir/rewrite/HomeShelfCoordinator.kt")

    private fun closeTomeToLibraryBody(): String {
        val code = homeShelfCoordinator()
        val startAnchor = "val closeTomeToLibrary: (arrivalFence: Long?) -> Boolean = close@{ arrivalFence ->"
        val endAnchor = "val completeTomeLibraryReturn: (fence: Long) -> Unit"
        assertTrue(
            "closeTomeToLibrary's definition anchor moved -- update this pin alongside it",
            code.contains(startAnchor),
        )
        assertTrue(
            "closeTomeToLibrary's end anchor moved -- update this pin alongside it",
            code.contains(endAnchor),
        )
        return code.substringAfter(startAnchor).substringBefore(endAnchor)
    }

    private fun completeTomeLibraryReturnBody(): String {
        val code = homeShelfCoordinator()
        val startAnchor = "val completeTomeLibraryReturn: (fence: Long) -> Unit = complete@{ fence ->"
        assertTrue(
            "completeTomeLibraryReturn's definition anchor moved -- update this pin alongside it",
            code.contains(startAnchor),
        )
        return code.substringAfter(startAnchor).substringBefore("LaunchedEffect(tomeSession?.tomeId")
    }

    private fun mimirAppCoordinator(): String =
        source("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppCoordinator.kt")

    private fun releaseEffectBody(): String {
        val code = mimirAppCoordinator()
        val startAnchor = "private fun HomeTomeLibraryReturnReleaseEffect("
        val endAnchor = "private fun MimirAppLifecycleEffect("
        assertTrue(
            "HomeTomeLibraryReturnReleaseEffect's definition anchor moved -- update this pin alongside it",
            code.contains(startAnchor),
        )
        assertTrue(
            "HomeTomeLibraryReturnReleaseEffect's end anchor moved -- update this pin alongside it",
            code.contains(endAnchor),
        )
        return code.substringAfter(startAnchor).substringBefore(endAnchor)
    }

    @Test
    fun `the four-frame open-loop clock is gone from closeTomeToLibrary`() {
        val body = closeTomeToLibraryBody()
        assertFalse(
            "repeat(4) { withFrameNanos {} } must not survive CP02 -- adding OverlayDissolveCoverMs's " +
                "130ms hold on TOP of the unchanged four-frame count reopens the exposed-shelf hole for " +
                "~60-100ms, which is worse than the defect CP02 fixes.",
            body.contains("repeat(4)"),
        )
    }

    @Test
    fun `closeTomeToLibrary launches no coroutine and does not itself read navigation state`() {
        val body = closeTomeToLibraryBody()
        assertFalse(
            "closeTomeToLibrary must not call scope.launch -- the wait moved to " +
                "HomeTomeLibraryReturnReleaseEffect (MimirAppCoordinator.kt) specifically so this " +
                "~2300-line composable does not grow a navigation-coordinator parameter again (the crash).",
            body.contains("scope.launch"),
        )
        assertFalse(
            "closeTomeToLibrary must not reference homeTomeLibraryOpenSettled -- it has no navigation " +
                "parameter to read it from after the crash-driven restructure, and must not regain one.",
            body.contains("homeTomeLibraryOpenSettled"),
        )
    }

    @Test
    fun `closeTomeToLibrary stores the caller's fence, or clears immediately when there is none`() {
        val body = closeTomeToLibraryBody()
        assertTrue(
            "a null arrivalFence (no incoming Library arrival to sequence against -- the deleted-tome " +
                "path) must clear the session immediately rather than wait for a bump that will never come.",
            Regex("""arrivalFence\s*==\s*null[\s\S]{0,80}tomeSession\s*=\s*null""").containsMatchIn(body),
        )
        assertTrue(
            "a non-null arrivalFence must be stored on tomeLibraryReturnPendingFence for the external " +
                "release effect to consume.",
            Regex("""tomeLibraryReturnPendingFence\s*=\s*arrivalFence""").containsMatchIn(body),
        )
    }

    @Test
    fun `the deleted-tome auto-close passes no arrival fence`() {
        val code = homeShelfCoordinator()
        assertTrue(
            "the deleted-tome LaunchedEffect calls closeTomeToLibrary(null) -- it never republishes " +
                "bindings.navigation.homeTomeLibrary, so there is no fresh arrival for a real fence to " +
                "name; passing anything else would hang this path (the liveness claim this file's " +
                "predecessor got wrong).",
            code.contains("closeTomeToLibrary(null)"),
        )
    }

    @Test
    fun `completeTomeLibraryReturn guards on fence equality before clearing the session`() {
        val body = completeTomeLibraryReturnBody()
        assertTrue(
            "a completion whose fence does not match the currently pending one must be a no-op -- " +
                "otherwise a stale completion could clear a NEWER return's session.",
            Regex("""tomeLibraryReturnPendingFence\s*!=\s*fence[\s\S]{0,40}return@""").containsMatchIn(body),
        )
        assertTrue(
            "a matching completion must actually clear tomeSession.",
            body.contains("tomeSession = null"),
        )
    }

    @Test
    fun `closeTomeSurface captures the fence synchronously and passes it as an argument`() {
        val code = source("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppInputDispatch.kt")
        assertTrue(
            "closeTomeSurface must read navigation.homeTomeLibraryOpenArrivalEpoch itself -- this plain, " +
                "non-@Composable function is where the fence must be captured, strictly before " +
                "recomposition can process the homeTomeLibrary = returnState write on the line above it.",
            code.contains("bindings.navigation.homeTomeLibraryOpenArrivalEpoch"),
        )
        assertTrue(
            "the captured fence must be passed as closeTomeToLibrary's argument, not dropped.",
            Regex("""closeTomeToLibrary\(arrivalFence\)""").containsMatchIn(code),
        )
    }

    @Test
    fun `the release effect waits on the epoch fence AND settled, OR the reversal escape`() {
        val body = releaseEffectBody()
        assertTrue(
            "the effect must read homeTomeLibraryOpenSettled -- the same signal CP01-a beat 3 hoisted " +
                "and the open hop's folioCovers release already reads.",
            body.contains("homeTomeLibraryOpenSettled"),
        )
        assertTrue(
            "the effect must read homeTomeLibraryOpenArrivalEpoch to compare against the stored fence.",
            body.contains("homeTomeLibraryOpenArrivalEpoch"),
        )
        assertTrue(
            "the wait must require BOTH the epoch to have advanced past the fence AND settled to be " +
                "true, OR release on the reversal escape -- any one condition alone reopens a hazard " +
                "(settled alone: a stale true from a previous arrival; epoch alone: an arrival not yet " +
                "settled; no escape: a hang if the browser is torn down mid-wait).",
            Regex("""epoch\s*>\s*fence[\s\S]{0,20}&&[\s\S]{0,20}settled[\s\S]{0,20}\|\|[\s\S]{0,40}browserGone""")
                .containsMatchIn(body),
        )
        assertTrue(
            "the reversal escape must read navigation.homeTomeLibrary == null -- the browser gone, " +
                "mirroring the open hop's `folio == null` arm.",
            body.contains("navigation.homeTomeLibrary == null"),
        )
        assertTrue(
            "on release the effect must call back into completeTomeLibraryReturn, not clear tomeSession " +
                "directly -- it has no access to that private local, by design.",
            body.contains("completeTomeLibraryReturn(fence)"),
        )
    }

    @Test
    fun `the arrival epoch and the settle latch share exactly one writer`() {
        val code = source("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt")
        assertTrue(
            "onIncomingSettleChanged must bump homeTomeLibraryOpenArrivalEpoch on the false-edge, beside " +
                "its existing homeTomeLibraryOpenSettled write -- two independent writers could drift " +
                "and let the fence and the latch describe different arrivals.",
            Regex(
                """onIncomingSettleChanged[\s\S]{0,700}homeTomeLibraryOpenArrivalEpoch[\s\S]{0,200}""" +
                    """homeTomeLibraryOpenSettled\s*=\s*settled""",
            ).containsMatchIn(code),
        )
    }

    @Test
    fun `MimirNavigationCoordinator declares the epoch beside the latch it fences`() {
        val code = source("src/main/kotlin/com/gruning/mimir/rewrite/MimirNavigationCoordinator.kt")
        assertTrue(
            "homeTomeLibraryOpenArrivalEpoch must exist as mutableStateOf state on the navigation " +
                "coordinator, the same shape as homeTomeLibraryOpenSettled beside it.",
            Regex("""var\s+homeTomeLibraryOpenArrivalEpoch\s+by\s+mutableStateOf""").containsMatchIn(code),
        )
    }

    @Test
    fun `rememberHomeShelfCoordinator does not take a navigation-coordinator parameter -- the crash guard`() {
        val code = homeShelfCoordinator()
        val fnStart = "internal fun rememberHomeShelfCoordinator("
        assertTrue(
            "rememberHomeShelfCoordinator's anchor moved -- update this pin alongside it",
            code.contains(fnStart),
        )
        val signature = code.substringAfter(fnStart).substringBefore("): HomeShelfCoordinator {")
        assertFalse(
            "rememberHomeShelfCoordinator must not gain a MimirNavigationCoordinator parameter -- that is " +
                "the exact change that pushed this ~2300-line composable past ART's verifier " +
                "Reach any " +
                "navigation signal through a caller-computed argument (see closeTomeToLibrary's " +
                "arrivalFence) or an effect extracted to a smaller composable instead.",
            signature.contains("MimirNavigationCoordinator"),
        )
    }
}
