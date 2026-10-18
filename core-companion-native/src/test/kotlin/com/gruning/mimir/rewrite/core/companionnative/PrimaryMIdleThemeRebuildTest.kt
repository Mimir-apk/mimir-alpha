package com.gruning.mimir.rewrite.core.companionnative

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The M button must not be rebuilt into its one-pixel idle window.
 *
 * Reported 2026-08-29: changing theme while the Mimir button was hidden prevented it
 * from reappearing. Reproduced, instrumented and A/B'd on the Odin before any code was written:
 *
 *  - Theme change while HIDDEN — probe read `addView flag=true alpha=0.0`. The replacement view was
 *    attached into the 1x1 `PrimaryIdleWatcherSizePx` window. The later reveal then completed
 *    *successfully* (`applyHidden DONE updated=true flag=false alpha=0.9 w=129 h=129`) and the button
 *    still never drew: the screen region stayed at luminance ~9 while every piece of state claimed the
 *    button was present and full size. That is why no amount of interaction brought it back — nothing
 *    was broken for the reveal path to fix.
 *  - Theme change while VISIBLE (control) — `addView flag=false alpha=0.9`, attached at full size, and
 *    the button survived: button-region luminance 44.7 -> 41.5.
 *
 * The 1x1 window is deliberate and must stay: it keeps ONE WindowManager input target across the whole
 * hide → observe → reveal cycle, and removing it made the Odin cancel the original touch headed to Mimir.
 * So the fix is not to change the hide geometry — it is to stop attaching a fresh view into it.
 *
 * Source-contract test: the defect is Android window/view lifecycle, which has no JVM-reachable
 * observable — the same reason the select-overlay layering tests are written this way.
 */
class PrimaryMIdleThemeRebuildTest {

    private val source: String by lazy {
        val file = File(
            "src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/" +
                "AndroidCompanionOverlayController.kt",
        )
        assertTrue("AndroidCompanionOverlayController.kt must be readable", file.isFile)
        file.readText()
    }

    /** Comment bodies stripped: the KDoc explaining this defect names the very calls it forbids. */
    private val code: String by lazy {
        source.lineSequence().joinToString("\n") { line ->
            val marker = line.indexOf("//")
            if (marker >= 0) line.substring(0, marker) else line
        }
    }

    @Test
    fun aThemeChangeWhileIdleHiddenDoesNotRebuildTheAttachedView() {
        val themeFn = code.substringAfter("private fun applyOverlayTheme(", "")
            .substringBefore("private fun ", "")

        assertTrue(
            "a theme arriving while idle-hidden must defer, not rebuild",
            "primaryIdleHidden" in themeFn && "themeRebuildDeferredWhileHidden = true" in themeFn,
        )
        assertTrue(
            "and must return before replaceView",
            themeFn.indexOf("themeRebuildDeferredWhileHidden = true") < themeFn.indexOf("replaceView("),
        )
    }

    @Test
    fun theDeferredThemeIsAppliedOnTheNextReveal() {
        val hiddenFn = code.substringAfter("private fun applyPrimaryIdleHidden(", "")
            .substringBefore("private fun ", "")

        assertTrue(
            "the reveal must flush a deferred theme rebuild",
            "themeRebuildDeferredWhileHidden" in hiddenFn && "applyOverlayTheme(overlayTheme)" in hiddenFn,
        )
        assertTrue(
            "and only when revealing, never when hiding",
            "!hidden && themeRebuildDeferredWhileHidden" in hiddenFn,
        )
    }

    /**
     * Ordering is load-bearing: `overlayParams` sizes the window from `primaryIdleHidden`, so the rebuild
     * must happen AFTER the flag is cleared and the window resized, or the replacement is attached into
     * the very 1x1 window this fix exists to avoid.
     */
    @Test
    fun theDeferredRebuildRunsAfterTheWindowIsRestored() {
        val hiddenFn = code.substringAfter("private fun applyPrimaryIdleHidden(", "")
            .substringBefore("private fun ", "")

        assertTrue(
            "the rebuild must follow updateViewLayout",
            hiddenFn.indexOf("updateViewLayout") < hiddenFn.indexOf("applyOverlayTheme(overlayTheme)"),
        )
    }

    /** The one-pixel watcher stays: it is the input target that survives the hide/reveal cycle. */
    @Test
    fun theIdleWatcherWindowIsUnchanged() {
        assertTrue("the 1px idle watcher must remain", "PrimaryIdleWatcherSizePx" in code)
    }
}
