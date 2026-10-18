package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O10's Android half, pinned as source because none of it runs in a JVM test: the two recovery moments that call the
 * once-per-process gate, MainActivity's creation closing it, the off-main read, and a start that is the component-less
 * HOME route on display 0. The behaviour is pinned by [MimirMainHomeRecoveryTest].
 *
 * Call sites are matched as whole trimmed lines, so a declaration or a comment can never satisfy them.
 */
class MimirMainHomeRecoveryWiringTest {

    /** Source with whole-line and block comments removed, so prose can neither satisfy nor break a pin. */
    private fun code(relative: String): String {
        val file = File(relative)
        assertTrue("$relative must be readable from the app module dir", file.isFile)
        return file.readText()
            .replace(Regex("""(?ms)^\s*/\*.*?\*/"""), "")
            .replace(Regex("""(?m)^\s*//.*$"""), "")
    }

    /** The text inside the first brace block that opens after [header], matched by depth. */
    private fun blockAfter(source: String, header: String): String {
        val start = source.indexOf(header)
        assertTrue("'$header' must exist", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("'$header' must open a block", open >= 0)
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
        throw AssertionError("unbalanced block after '$header'")
    }

    private fun lines(block: String): List<String> = block.lines().map(String::trim)

    @Test
    fun `the process start arms the gate before a persisted Bottom lease is adopted, and ProcessRecovery triggers it`() {
        val create = blockAfter(code(RUNTIME), "fun create(")
        val createLines = lines(create)
        val arm = createLines.indexOf("MimirMainHomeRecovery.arm(context)")
        val adopt = createLines.indexOf("if (CompanionReturnCoordinator.recoverPersistedNonMirror(pending)) {")
        assertTrue("create() must arm Main's recovery", arm >= 0)
        assertTrue("create() must still adopt the persisted lease", adopt >= 0)
        assertTrue("arm before the adoption, or ProcessRecovery would trigger an unarmed gate", arm < adopt)
        val recovered = lines(blockAfter(create, "if (CompanionReturnCoordinator.recoverPersistedNonMirror(pending))"))
        assertTrue(
            "ProcessRecovery must trigger Main's recovery inside the branch that adopted the lease",
            "MimirMainHomeRecovery.trigger(MainHomeRecoveryTrigger.PROCESS_RECOVERY)" in recovered,
        )
    }

    @Test
    fun `the floor resuming on display 4 triggers it after the Bottom's own resume decision`() {
        val resume = lines(blockAfter(code(FLOOR), "override fun onResume("))
        val bottom = resume.indexOf("CompanionReturnCoordinator.noteSecondaryHomeResumed(display?.displayId)")
        val main = resume.indexOf("MimirMainHomeRecovery.trigger(MainHomeRecoveryTrigger.FLOOR_RESUMED)")
        assertTrue("the Bottom's resume decision stays", bottom >= 0)
        assertTrue("the floor's resume must trigger Main's recovery", main >= 0)
        assertTrue("after the Bottom's decision, which it must not change", main > bottom)
    }

    @Test
    fun `MainActivity's creation closes the window and the decision runs off the main thread`() {
        val recovery = code(RECOVERY)
        val arm = blockAfter(recovery, "fun arm(")
        assertTrue("arm must watch for MainActivity's creation", "registerActivityLifecycleCallbacks(" in arm)
        assertTrue("UsageStats must not be queried on the main thread", "Dispatchers.IO" in arm)
        val created = lines(blockAfter(recovery, "override fun onActivityCreated("))
        assertTrue("only MainActivity closes the window", "if (activity !is MainActivity) return" in created)
        assertTrue("its creation must reach the gate", "gate.noteMainActivityCreated()" in created)
    }

    @Test
    fun `the start is a component-less HOME intent in Mimir's package on display 0 and nothing else`() {
        val start = blockAfter(code(RECOVERY), "override fun start(")
        val startLines = lines(start)
        listOf(
            "val intent = Intent(request.action)",
            ".addCategory(request.category)",
            ".setPackage(request.packageName)",
            ".addFlags(request.flags)",
            "val options = ActivityOptions.makeBasic().setLaunchDisplayId(request.launchDisplayId)",
            "context.startActivity(intent, options.toBundle())",
        ).forEach { line -> assertTrue("start must contain the line: $line", line in startLines) }
        mapOf(
            "setComponent" to "an explicit component is typed STANDARD (am start -n gave MainActivity a type=standard task)",
            "setClassName" to "same as setComponent",
            "ComponentName" to "same as setComponent",
            "MainActivity" to "the HOME route resolves Main; naming it is the explicit start",
            "FLAG_ACTIVITY_MULTIPLE_TASK" to "one Main, never a second",
            "Intent.CATEGORY" to "the category comes from the pinned request only",
        ).forEach { (needle, why) -> assertFalse("start must not contain $needle: $why", needle in start) }
    }

    private companion object {
        const val RUNTIME = "src/main/kotlin/com/gruning/mimir/rewrite/CompanionProcessRuntime.kt"
        const val FLOOR = "src/main/kotlin/com/gruning/mimir/rewrite/CompanionSecondaryHomeActivity.kt"
        const val RECOVERY = "src/main/kotlin/com/gruning/mimir/rewrite/MimirMainHomeRecovery.kt"
    }
}
