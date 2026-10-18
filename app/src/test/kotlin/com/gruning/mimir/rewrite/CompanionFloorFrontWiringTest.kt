package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task A, floor first: the Android half, pinned as source because none of it runs in a JVM test. The restore guard
 * finishes on the publish inside `presentationShown` (`ReturnHostExecutionState.dispositionDecision` COMPLETE), so the
 * floor has to be asked for before that call; once the guard is gone the next focusable window on the display is whatever
 * sits under it, and on the Thor on 2026-09-11 that was YouTube holding display 4's key focus. The start is the shape the
 * Thor's own framework used to place this floor (`act=MAIN cat=[SECONDARY_HOME] pkg=com.gruning.mimir.rewrite`), never an explicit component.
 */
class CompanionFloorFrontWiringTest {

    /** Source with whole-line and block comments removed, so prose can neither satisfy nor break a pin. */
    private fun code(relative: String): String {
        val file = File(relative)
        assertTrue("$relative must be readable from the app module dir", file.isFile)
        return file.readText()
            .replace(Regex("""(?ms)^\s*/\*.*?\*/"""), "")
            .replace(Regex("""(?m)^\s*//.*$"""), "")
    }

    private fun controller(): String = code("src/main/kotlin/com/gruning/mimir/rewrite/CompanionDisplayController.kt")

    /** The text inside the brace block of `fun <name>(`, matched by depth. */
    private fun blockBody(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name( must exist", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("fun $name( must have a block body", open >= 0)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
        }
        throw AssertionError("unbalanced body for $name")
    }

    /** A top-level declaration's text, from `fun <name>(` to the next top-level item. */
    private fun topLevel(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name( must exist", start >= 0)
        val next = Regex("""(?m)^(internal |private |public |fun |class |object |@)""").find(source, start + 1)
        return source.substring(start, next?.range?.first ?: source.length)
    }

    @Test
    fun `the frame-commit settle path asks for the floor before presentationShown publishes`() {
        val settle = blockBody(controller(), "handleFrameCommitted")
        val ask = settle.indexOf("floorFrontReclaim(")
        val bring = settle.indexOf("requestFloorFront(")
        val publish = settle.indexOf("presentationShown(")
        assertTrue("handleFrameCommitted must ask the runtime which reclaim is settling", ask >= 0)
        assertTrue("then bring the floor forward with that lease", bring > ask)
        assertTrue("both before presentationShown, whose publish is what the restore guard finishes on", publish > bring)
    }

    @Test
    fun `the request checks first then starts on the reclaimed display and logs one line`() {
        val request = blockBody(controller(), "requestFloorFront")
        listOf(
            "companionFloorFrontIntent(activity.packageName)",
            "launchDisplayId = lease.displayId",
            "activity.startActivity(intent, options.toBundle())",
            "Log.i(\"MimirCompanionRuntime\", floorFrontLogLine(lease.displayId, lease.reason, lease.operationId, result))",
        ).forEach { needle -> assertTrue("requestFloorFront must contain: $needle", needle in request) }
        val precheck = request.indexOf("floorFrontPrecheckFailure(")
        assertTrue("the precheck must run before the start", precheck >= 0 && precheck < request.indexOf("startActivity("))
        assertFalse("never a start aimed at the default display", "DEFAULT_DISPLAY" in request)
    }

    @Test
    fun `the floor intent is component-less SECONDARY_HOME in Mimir's package and nothing else`() {
        val intent = topLevel(controller(), "companionFloorFrontIntent")
        listOf(
            "Intent(Intent.ACTION_MAIN)",
            ".addCategory(Intent.CATEGORY_SECONDARY_HOME)",
            ".setPackage(packageName)",
            "Intent.FLAG_ACTIVITY_NEW_TASK",
        ).forEach { needle -> assertTrue("companionFloorFrontIntent must contain: $needle", needle in intent) }
        mapOf(
            "setComponent" to "an explicit component is not typed HOME and can join another task by affinity",
            "setClassName" to "same as setComponent",
            "ComponentName" to "same as setComponent",
            "Intent.CATEGORY_HOME" to "one category only, and HOME belongs to Main",
            "CATEGORY_DEFAULT" to "one category only",
            "MainActivity" to "the floor request never names Main",
            "FLAG_ACTIVITY_MULTIPLE_TASK" to "reuse the existing floor, never a second one",
        ).forEach { (needle, why) -> assertFalse("companionFloorFrontIntent must not contain $needle: $why", needle in intent) }
    }

    @Test
    fun `the floor starts nothing when it is brought forward`() {
        val floor = code("src/main/kotlin/com/gruning/mimir/rewrite/CompanionSecondaryHomeActivity.kt")
        listOf("startActivity", "startActivities", "moveTaskToFront", "moveToFront").forEach { needle ->
            assertFalse("onNewIntent and onResume run on every floor-front, so the floor must not call $needle", needle in floor)
        }
    }
}
