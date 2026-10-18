package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On SWAP, Main cuts the way the Bottom does. Every start Mimir issues on Main as part
 * of SWAP carries no window animation, so the app appears at once.
 *
 * Observed on Thor test unit, build 4220c723: the SWAP up start of the Bottom app on display 0 logged
 * `START … flg=0x30220000`, with no NO_ANIMATION bit, and Main recorded a ~340 ms slide. The transfer host already called
 * `overridePendingTransition(0, 0)` right after that start. The Bottom's host starts carry `FLAG_ACTIVITY_NO_ANIMATION`
 * and cut.
 *
 * No JVM test can watch a window transition, so each start site is read from source, with comments removed and
 * whitespace collapsed so prose can neither satisfy nor break a pin. What these pins do not claim: that Android honours
 * the flag on a device.
 */
class CompanionSwapMainStartCutTest {

    private fun sourceFile(vararg candidates: String): File =
        candidates.map(::File).firstOrNull(File::isFile)
            ?: throw AssertionError("none of ${candidates.toList()} is readable from ${File(".").absolutePath}")

    private fun appSource(name: String): File = sourceFile(
        "src/main/kotlin/com/gruning/mimir/rewrite/$name",
        "app/src/main/kotlin/com/gruning/mimir/rewrite/$name",
    )

    private fun code(file: File): String = file.readText()
        .replace(Regex("""(?ms)^\s*/\*.*?\*/"""), "")
        .replace(Regex("""(?m)^\s*//.*$"""), "")
        .replace(Regex("""\s+"""), " ")

    /** The text between the first [open] at or after [anchor] and its matching [close]. */
    private fun enclosed(source: String, anchor: String, open: Char, close: Char): String {
        val at = source.indexOf(anchor)
        assertTrue("anchor not found: $anchor", at >= 0)
        val start = source.indexOf(open, at)
        assertTrue("no '$open' after: $anchor", start >= 0)
        var depth = 0
        for (index in start until source.length) {
            when (source[index]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return source.substring(start + 1, index)
                }
            }
        }
        throw AssertionError("unbalanced '$open' after: $anchor")
    }

    private fun block(source: String, anchor: String): String = enclosed(source, anchor, '{', '}')

    private fun arguments(source: String, anchor: String): String = enclosed(source, anchor, '(', ')')

    private fun assertInOrder(message: String, text: String, vararg parts: String) {
        var from = 0
        parts.forEach { part ->
            val at = text.indexOf(part, from)
            assertTrue("$message: expected `$part` at or after $from in: $text", at >= 0)
            from = at + part.length
        }
    }

    @Test
    fun `SWAP up starts the Bottom app on Main without animation`() {
        val body = block(code(appSource("PrimaryTransferActivity.kt")), "private fun dispatchTarget(")
        val flags = block(body, "val launchIntent = resolveTargetLaunchIntent()?.apply")
        listOf(
            "Intent.FLAG_ACTIVITY_NEW_TASK",
            "Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED",
            "Intent.FLAG_ACTIVITY_REORDER_TO_FRONT",
            "Intent.FLAG_ACTIVITY_SINGLE_TOP",
        ).forEach { flag -> assertTrue("the target start keeps $flag, flags were: $flags", flag in flags) }
        assertTrue(
            "the SWAP up start of the Bottom app on display 0 carries no FLAG_ACTIVITY_NO_ANIMATION, so Main plays the " +
                "task-to-front slide (rank 4d, flg=0x30220000); the overridePendingTransition(0, 0) after the start did " +
                "not stop it. Flags were: $flags",
            "Intent.FLAG_ACTIVITY_NO_ANIMATION" in flags,
        )
        assertInOrder(
            "the target still starts on Main",
            body,
            "ActivityOptions.makeBasic().apply { launchDisplayId = Display.DEFAULT_DISPLAY }",
            "startActivity(launchIntent, options.toBundle())",
        )
    }

    @Test
    fun `the transfer host itself still starts on Main without animation`() {
        val registry = code(
            sourceFile(
                "../core-companion-native/src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/" +
                    "AndroidSecondaryTaskRegistry.kt",
                "core-companion-native/src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/" +
                    "AndroidSecondaryTaskRegistry.kt",
            ),
        )
        val hostIntent = registry
            .substringAfter("private fun primaryTransferHostIntent(", missingDelimiterValue = "")
            .substringBefore("private fun ")
        assertTrue(
            "the display-0 transfer host start keeps NEW_TASK | NO_ANIMATION (Thor START flg=0x10010000), was: $hostIntent",
            ".addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)" in hostIntent,
        )
        assertTrue(
            "a display-0 launch still goes through the transfer host intent",
            "primaryTransferHostIntent(clean, preparedOperationId ?: return false)" in
                block(registry, "private fun launchOnDisplay("),
        )
    }

    @Test
    fun `the transfer host closes without animation on every API level`() {
        val body = block(code(appSource("PrimaryTransferActivity.kt")), "private fun finishWithoutAnimation()")
        val api34 = block(body, "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)")
        assertInOrder(
            "on API 34+ the close override is set before the host closes",
            api34,
            "overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)",
            "finish()",
        )
        assertInOrder(
            "below API 34 the host keeps finish() then overridePendingTransition(0, 0)",
            block(body, "} else"),
            "finish()",
            "overridePendingTransition(0, 0)",
        )
    }

    @Test
    fun `the exchange brings the former Bottom app up on Main without animation`() {
        val body = block(code(appSource("MainActivity.kt")), "private fun exchangeCompanionApps(")
        assertEquals(
            "the exchange makes one Main route dispatch",
            1,
            Regex(Regex.escape("CompanionQuickLaunchPrimaryRoute.dispatch(")).findAll(body).count(),
        )
        val legThree = arguments(body, "CompanionQuickLaunchPrimaryRoute.dispatch(")
        assertTrue("leg 3 still dispatches the former Bottom app, was: $legThree", "bottomHost.packageName" in legThree)
        assertTrue(
            "leg 3 of the two-app exchange starts the former Bottom app on Main through the Quick launch route, which " +
                "animates by default; as part of SWAP it asks for noAnimation = true. Arguments were: $legThree",
            "noAnimation = true" in legThree,
        )
    }

    @Test
    fun `the Main route forwards a no-animation request and animates by default`() {
        val route = code(appSource("CompanionQuickLaunchPrimaryRoute.kt"))
        val parameters = arguments(route, "fun dispatch(")
        assertTrue(
            "dispatch takes noAnimation, off by default, parameters were: $parameters",
            "noAnimation: Boolean = false" in parameters,
        )
        assertTrue(
            "dispatch hands the request to the installed handler",
            "handler?.invoke(packageName.trim(), fromGeneral, noAnimation)" in route,
        )
    }

    @Test
    fun `the Main route's start receives the request and the Home picker passes none`() {
        val coordinator = code(appSource("MimirAppCoordinator.kt"))
        assertTrue(
            "the Main route's transaction takes the request as its last argument",
            "val launchHomeQuickPackageOnPrimary: (String, Boolean, CompanionOverlayRole, Boolean) -> " +
                "QuickLaunchPrimaryRouteResult = launch@{ packageName, fromGeneral, sourceRole, noAnimation ->" in coordinator,
        )
        val start = arguments(block(coordinator, "val launchHomeQuickPackageOnPrimary:"), "startNativeShelfLaunchIntent(")
        assertTrue("the start stays on Main, arguments were: $start", "launchDisplayId = Display.DEFAULT_DISPLAY" in start)
        assertTrue("the start carries the request, arguments were: $start", "noAnimation = noAnimation" in start)
        assertTrue(
            "the installed route handler forwards the request",
            "launchQuickPackageOnMain = { packageName, fromGeneral, noAnimation -> " +
                "launchHomeQuickPackageOnPrimary(packageName, fromGeneral, CompanionOverlayRole.SECONDARY_COMPANION, noAnimation) }" in coordinator,
        )
        assertTrue(
            "the Home picker's own Main launch keeps the animation",
            "launchHomeQuickPackageOnPrimary(packageName, fromGeneral, CompanionOverlayRole.PRIMARY_SINGLE, false)" in coordinator,
        )
    }

    @Test
    fun `the native start adds NO_ANIMATION only when asked`() {
        val shelf = code(appSource("NativeShelfPackages.kt"))
        val parameters = arguments(shelf, "fun startNativeShelfLaunchIntent(")
        assertTrue(
            "the native start takes noAnimation, off by default, parameters were: $parameters",
            "noAnimation: Boolean = false" in parameters,
        )
        assertTrue(
            "the native start applies the request to the intent it starts",
            "Intent(target).applyNativeShelfLaunchFlags(noAnimation)" in block(shelf, "fun startNativeShelfLaunchIntent("),
        )
        val flags = shelf
            .substringAfter("internal fun nativeShelfLaunchFlags(", missingDelimiterValue = "")
            .substringBefore("private fun ")
        assertTrue(
            "NO_ANIMATION joins the shipping task flags only on request, was: $flags",
            "noAnimation: Boolean = false" in flags &&
                "(if (noAnimation) Intent.FLAG_ACTIVITY_NO_ANIMATION" in flags,
        )
    }

    @Test
    fun `Quick launch from the Bottom keeps its window animation`() {
        listOf("CompanionRecentsOverlayHost.kt", "MimirCompanionPresentation.kt").forEach { name ->
            assertTrue(
                "$name: Quick launch's own dispatch asks for no cut (O12 is SWAP only)",
                "CompanionQuickLaunchPrimaryRoute.dispatch(packageName, fromGeneral)" in code(appSource(name)),
            )
        }
    }
}
