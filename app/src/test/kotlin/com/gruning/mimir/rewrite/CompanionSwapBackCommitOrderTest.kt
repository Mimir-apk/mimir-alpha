package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device result (Thor, build 5113d5e4, 2026-09-12 01:56 and 02:02, 2 of 2): SWAP back still
 * animated on Main. The leftover Main copy was removed about 70 ms after MainActivity resumed, before the no-animation
 * reorder's transition had run, so the copy was still visible. In AOSP 13, finishing a visible activity prepares
 * TRANSIT_CLOSE (ActivityRecord.java:3407 for a resumed one; 3445-3448 and 3487-3492 for a visible one), and the pending
 * transition becomes a task close: Home slides in from the left (task_close_enter, -105% over 500 ms).
 *
 * The copy is removed only once the system reports Mimir's Main task visible. That is committed visibility:
 * TaskInfo.isVisible comes from Task.hasVisibleChildren (Task.java:3473, 4329-4334), which reads ActivityRecord.mVisible,
 * set only when a visibility change is committed (ActivityRecord.java:4954-4967).
 *
 * Source pins; comments are removed and whitespace collapsed.
 */
class CompanionSwapBackCommitOrderTest {

    private fun appSource(name: String): String {
        val file = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        ).firstOrNull(File::isFile) ?: throw AssertionError("$name is not readable from ${File(".").absolutePath}")
        return file.readText()
            .replace(Regex("""(?ms)^\s*/\*.*?\*/"""), "")
            .replace(Regex("""(?m)^\s*//.*$"""), "")
            .replace(Regex("""\s+"""), " ")
    }

    private fun block(source: String, anchor: String): String {
        val at = source.indexOf(anchor)
        assertTrue("anchor not found: $anchor", at >= 0)
        val start = source.indexOf('{', at)
        var depth = 0
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start + 1, index)
                }
            }
        }
        throw AssertionError("unbalanced block after: $anchor")
    }

    private fun assertInOrder(message: String, text: String, vararg parts: String) {
        var from = 0
        parts.forEach { part ->
            val at = text.indexOf(part, from)
            assertTrue("$message: expected `$part` at or after $from in: $text", at >= 0)
            from = at + part.length
        }
    }

    @Test
    fun `the front step returns only once the system reports Mimir's Main task visible`() {
        val front = block(appSource("CompanionMainCopyEviction.kt"), "internal suspend fun bringCompanionMainForward(")
        assertInOrder(
            "MainActivity resuming is not the reorder's transition having run; the copy removed in between was still " +
                "visible and its close became a task close. Wait for committed visibility, with a bound",
            front,
            "context.startActivity(",
            "withTimeoutOrNull(CompanionMainFrontResumeTimeoutMs)",
            "withTimeoutOrNull(CompanionMainFrontVisibleTimeoutMs)",
            "visibility = companionMainTaskVisible(context)",
            "if (visibility != false) break",
            "delay(CompanionMainFrontVisiblePollMs)",
            "mainVisible=",
        )
    }

    @Test
    fun `a Main task that cannot be read is logged as unreadable, never as visible`() {
        val front = block(appSource("CompanionMainCopyEviction.kt"), "internal suspend fun bringCompanionMainForward(")
        assertTrue(
            "the visible wait also ends when the task cannot be read (null); the log must say so, not `true`. " +
                "Front step was: $front",
            "visibility?.toString() ?: \"unreadable\"" in front,
        )
        assertTrue(
            "round 5b v4 printed `mainVisible=true` for a task that could not be read. Front step was: $front",
            "visible.toString()" !in front,
        )
    }

    @Test
    fun `Main task visibility is the committed visibility the system reports for Mimir's own task`() {
        val visible = block(
            appSource("CompanionMainCopyEviction.kt"),
            "internal fun companionMainTaskVisible(context: Context): Boolean?",
        )
        assertInOrder(
            "read from the caller's own running tasks, on API 32+ where TaskInfo.isVisible is readable",
            visible,
            "Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2",
            "task.baseActivity?.className == MainActivity::class.java.name",
            "?.isVisible",
        )
    }
}
