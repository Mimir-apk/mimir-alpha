package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D34b: the trackpad's Accessibility service can place taps and swipes and nothing else. These source pins hold the
 * facts the consent copy promises ("It does not read your screen, your typing or your passwords"), so a later
 * edit that widens the service fails here instead of shipping silently.
 */
class MimirTrackpadServiceXmlTest {

    private fun file(vararg candidates: String): File =
        candidates.map(::File).first { it.isFile }

    private val serviceXml = file(
        "src/main/res/xml/mimir_trackpad_service.xml",
        "app/src/main/res/xml/mimir_trackpad_service.xml",
    ).readText()

    private fun code(path: String) = file(path, "app/$path").readText()
        .replace(Regex("/[*].*?[*]/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//.*"), "")

    @Test
    fun theServiceCanPerformGesturesAndCannotReadTheScreenOrListenForEvents() {
        // The prose in the file's comment names what is absent; the declaration is what counts.
        val declaration = serviceXml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertTrue(declaration.contains("android:canPerformGestures=\"true\""))
        assertFalse("must not retrieve window content", declaration.contains("canRetrieveWindowContent"))
        assertFalse("must listen for no event types", declaration.contains("accessibilityEventTypes"))
        assertFalse("must not filter keys", declaration.contains("canRequestFilterKeyEvents"))
        assertFalse("must not explore by touch", declaration.contains("canRequestTouchExplorationMode"))
        assertFalse("must not take screenshots", declaration.contains("canTakeScreenshot"))
    }

    @Test
    fun theManifestDeclaresItBoundToTheSystemOnly() {
        val manifest = file("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").readText()
        val service = manifest.substringAfter("android:name=\".MimirTrackpadAccessibilityService\"")
            .substringBefore("</service>")
        assertTrue(service.contains("android.permission.BIND_ACCESSIBILITY_SERVICE"))
        assertTrue(service.contains("@xml/mimir_trackpad_service"))
    }

    @Test
    fun theServiceCodeNeverReadsTheScreenAndNoBridgeOpIsInvolved() {
        val source = code("src/main/kotlin/com/gruning/mimir/rewrite/MimirTrackpadAccessibilityService.kt")
        listOf(
            "rootInActiveWindow", "getRootInActiveWindow", "AccessibilityNodeInfo", "findFocus", "getWindows",
            "takeScreenshot", "performGlobalAction", "AccessibilityEvent.TYPE", "VendorPServer", "CertifiedPrivilege",
            "ShizukuProvider",
        ).forEach { forbidden ->
            assertFalse("the trackpad service must not use $forbidden", source.contains(forbidden))
        }
        assertTrue("injection is pinned to Main", source.contains("setDisplayId(Display.DEFAULT_DISPLAY)"))
    }

    @Test
    fun theInjectionRulesAreFrameworkFreeSoTheyStayTestable() {
        val injector = code("src/main/kotlin/com/gruning/mimir/rewrite/MimirTrackpadInjector.kt")
        assertFalse("the injector is plain Kotlin", injector.contains("import android."))
        assertTrue("every entry is session-guarded", injector.contains("session = 0L"))
    }
}
