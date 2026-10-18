package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two notification starts of MainActivity follow D1 (QA 2026-09-11, [mButtonAuthorityLaunchDisplayId]): a
 * MainActivity start Mimir issues names the Main display instead of leaving the display to whoever sends it. Both are
 * `PendingIntent`s built inside a foreground service, which no JVM test can post and tap, so the call itself is the
 * instrument: exactly one `PendingIntent.getActivity(` per file, request code, target, flags and mutability unchanged,
 * and a fifth argument carrying `setLaunchDisplayId(Display.DEFAULT_DISPLAY)`.
 *
 * What this does not claim: in Android 13 the sender's options are laid over the creator's
 * (`PendingIntentRecord.sendInner` -> `SafeActivityOptions.mergeActivityOptions`), and SystemUI sends a notification tap
 * with its own display as the launch display (`CentralSurfaces.getActivityOptions`). The display pinned here decides
 * only when the sender passes none.
 */
class MainActivityNotificationLaunchDisplayTest {

    /** Source without whole-line and block comments, whitespace collapsed, so prose can neither satisfy nor break a pin. */
    private fun code(relative: String): String {
        val file = File(relative)
        assertTrue("$relative must be readable from the app module dir", file.isFile)
        return file.readText()
            .replace(Regex("""(?ms)^\s*/\*.*?\*/"""), "")
            .replace(Regex("""(?m)^\s*//.*$"""), "")
            .replace(Regex("""\s+"""), " ")
    }

    /** The top-level arguments of the file's only `PendingIntent.getActivity(` call, split at depth-one commas. */
    private fun pendingIntentArguments(relative: String): List<String> {
        val source = code(relative)
        val calls = Regex("""PendingIntent\.getActivity\(""").findAll(source).toList()
        assertEquals("$relative must build exactly one activity PendingIntent", 1, calls.size)
        val open = calls.single().range.last
        val arguments = mutableListOf<String>()
        var depth = 0
        var start = open + 1
        for (index in open until source.length) {
            when (source[index]) {
                '(' -> depth++
                ',' -> if (depth == 1) {
                    arguments += source.substring(start, index).trim()
                    start = index + 1
                }
                ')' -> {
                    depth--
                    if (depth == 0) {
                        arguments += source.substring(start, index).trim()
                        return arguments.filter { it.isNotEmpty() }
                    }
                }
            }
        }
        throw AssertionError("unbalanced PendingIntent.getActivity( in $relative")
    }

    private fun assertOpensMainActivityOnTheMainDisplay(relative: String) {
        val arguments = pendingIntentArguments(relative)
        assertEquals("$relative: the request code stays 0", "0", arguments.getOrNull(1))
        val target = arguments.getOrNull(2).orEmpty()
        assertTrue(
            "$relative: the target stays MainActivity with CLEAR_TOP | SINGLE_TOP, was: $target",
            "Intent(this, MainActivity::class.java)" in target &&
                "Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP" in target,
        )
        assertEquals(
            "$relative: mutability and update flags stay as they were",
            "PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT",
            arguments.getOrNull(3),
        )
        val options = arguments.getOrNull(4)
        assertTrue(
            "$relative: the MainActivity PendingIntent names no launch display (options=$options), so a sender that " +
                "passes none leaves the display to the framework's fallback instead of Main. Pass " +
                "ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(), as D1 does.",
            options != null && Regex(
                """ActivityOptions\.makeBasic\(\) ?\.setLaunchDisplayId\(Display\.DEFAULT_DISPLAY\) ?\.toBundle\(\)""",
            ).matches(options),
        )
        assertEquals("$relative: nothing after the options argument", 5, arguments.size)
    }

    @Test
    fun `the M-button notification opens MainActivity on the Main display`() {
        assertOpensMainActivityOnTheMainDisplay("src/main/kotlin/com/gruning/mimir/rewrite/MButtonKeepAliveService.kt")
    }

    @Test
    fun `the performance overlay notification opens MainActivity on the Main display`() {
        assertOpensMainActivityOnTheMainDisplay("src/main/kotlin/com/gruning/mimir/rewrite/PerformanceOverlayService.kt")
    }
}
