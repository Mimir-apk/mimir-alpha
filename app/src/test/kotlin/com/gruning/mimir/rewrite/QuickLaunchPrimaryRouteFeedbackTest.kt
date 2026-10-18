package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 5 (5b), follow-up: the Main quick-launch route (`launchHomeQuickPackageOnPrimary` in MimirAppCoordinator.kt) made its
 * refusal and start-failure toasts from Main's Activity or the process context, so a request from the Bottom was answered on
 * Main while the Bottom surface that sent it reported the refusal again on its own display: one tap, a toast on each screen.
 *
 * The route takes the screen the request came from. For a Bottom request it makes no refusal toast and answers with its
 * reason ([QuickLaunchPrimaryRouteResult.Refused]); the start failures that come after it accepted, which no caller reports,
 * it makes on the Bottom. Main's own Home quick launch, the one caller with no Bottom display, is answered on Main as before.
 *
 * Each Bottom caller then makes the tap's one toast from that answer, on the display the player tapped. The Tabs drawer, the
 * Presentation's quick-launch rails and choice sheets and the Bottom Quick launch window do it through
 * [routeSecondaryQuickLaunchTap] and [settleSecondaryQuickLaunchChoiceTap], which show the route's reason when it gave one
 * (behaviour-tested in [SecondaryQuickLaunchTapFeedbackTest]). SWAP's last leg keeps its own sentence, which names the move
 * the player asked for.
 *
 * These are source pins: the route is a closure inside the app's composition and its callers are lambdas inside composables
 * and a MainActivity coroutine, none of which a JVM test here can drive (the gap [SessionMutexRefusalWiringTest] documents
 * for the route's closure).
 */
class QuickLaunchPrimaryRouteFeedbackTest {

    private val coordinator: List<String> = source("MimirAppCoordinator.kt")

    @Test
    fun `the Main route takes the screen the request came from and answers with a result`() {
        assertTrue(
            "launchHomeQuickPackageOnPrimary is declared with the source role of its request and returns the route's answer",
            coordinator.any {
                it.trim() == "val launchHomeQuickPackageOnPrimary: (String, Boolean, CompanionOverlayRole, Boolean) -> " +
                    "QuickLaunchPrimaryRouteResult = launch@{ packageName, fromGeneral, sourceRole, noAnimation ->"
            },
        )
    }

    @Test
    fun `a request through the Bottom's route is marked as coming from the Bottom`() {
        val entry = coordinator.block(start = "launchQuickPackageOnMain = { packageName, fromGeneral, noAnimation ->", end = "},")
        assertTrue(
            "the handler installed on CompanionQuickLaunchPrimaryRoute passes SECONDARY_COMPANION; the handler reads $entry",
            entry.any {
                it.trim() ==
                    "launchHomeQuickPackageOnPrimary(packageName, fromGeneral, CompanionOverlayRole.SECONDARY_COMPANION, noAnimation)"
            },
        )
    }

    @Test
    fun `Main's own Home quick launch is marked as coming from Main`() {
        val body = coordinator.block(
            start = "val launchHomeQuickPackage: (String, Boolean) -> Boolean = launch@{ packageName, fromGeneral ->",
            end = "val handleCustomEnrichTransition: (",
        )
        assertTrue(
            "with no Bottom display, Home's quick launch passes PRIMARY_SINGLE",
            body.any {
                it.trim() == "launchHomeQuickPackageOnPrimary(packageName, fromGeneral, CompanionOverlayRole.PRIMARY_SINGLE, false)"
            },
        )
    }

    @Test
    fun `the route answers each refusal with its reason and toasts it only for Main's own request`() {
        val body = routeBody()
        val toasts = body.filter { it.contains("showMimirMessage(core.context, ") }.map { it.trim() }
        assertEquals("the route makes its refusal toast in one place, found $toasts", 1, toasts.size)
        val refused = body.indexOfFirst { it.trim() == "fun refused(reason: String): QuickLaunchPrimaryRouteResult {" }
        assertTrue("the refusals share `fun refused(reason: String): QuickLaunchPrimaryRouteResult`", refused >= 0)
        assertEquals(
            "a refusal is toasted only for Main's own request",
            "if (reportsRefusal) showMimirMessage(core.context, reason)",
            body[refused + 1].trim(),
        )
        assertEquals(
            "and every request, a Bottom one included, is answered with the reason",
            "return QuickLaunchPrimaryRouteResult.Refused(reason)",
            body[refused + 2].trim(),
        )
        assertTrue(
            "the route decides from the source role whether it reports a refusal",
            body.any { it.trim() == "val reportsRefusal = sourceRole == CompanionOverlayRole.PRIMARY_SINGLE" },
        )
        val returns = body.map { it.trim() }.filter { it.startsWith("return@launch ") }
        assertEquals(
            "four refusals (the Library's launch still starting, the Library's game already running, a blank package and a " +
                "held session), each answered through refused(); the route's early returns are $returns",
            listOf(true, true, true, true),
            returns.map { it.startsWith("return@launch refused(") },
        )
        assertTrue(
            "the held session answers with the sentence for what held it (O15)",
            "return@launch refused(gameLaunchSessionHeldMessage(heldBy))" in returns,
        )
        assertTrue("an admitted request is answered Accepted", body.any { it.trim() == "QuickLaunchPrimaryRouteResult.Accepted" })
    }

    @Test
    fun `a start failure after the route accepted is made on the display the request came from`() {
        val body = routeBody()
        assertTrue(
            "no start failure is made from the bare process context",
            body.none { it.contains("Toast.makeText(appContext, ") || it.contains("showMimirMessage(appContext, ") },
        )
        val failures = body.indices.filter { body[it].trim() == "\"App couldn’t be launched\"," }
        assertEquals("two start failures: no launchable intent and an Activity that did not start", 2, failures.size)
        failures.forEach { index ->
            assertEquals("showMimirMessage(", body[index - 2].trim())
            assertEquals(
                "made from the context of the display the request came from",
                "companionFeedbackContext(appContext, failureDisplayId),",
                body[index - 1].trim(),
            )
        }
        val choice = body.indexOfFirst {
            it.trim() == "val failureDisplayId = if (sourceRole == CompanionOverlayRole.SECONDARY_COMPANION) {"
        }
        assertTrue("the failure display is chosen from the source role", choice >= 0)
        assertEquals(
            "a Bottom request's failures go to the selected secondary display, which the Bottom surfaces are on",
            "CompanionProcessRuntime.services(appContext).secondaryDisplaySelector.snapshot.value.selectedId",
            body[choice + 1].trim(),
        )
        assertEquals("} else {", body[choice + 2].trim())
        assertEquals("a Main request's failures keep the process context", "null", body[choice + 3].trim())
    }

    @Test
    fun `every Bottom caller of the Main route makes one toast for a refused request, from the route's answer`() {
        val callers = File(MainSourceDir).walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "CompanionQuickLaunchPrimaryRoute.kt" }
            .filter { it.readText().contains("CompanionQuickLaunchPrimaryRoute.dispatch(") }
            .map { it.name }
            .toSortedSet()
        assertEquals(
            "the route stays silent on a Bottom request's refusal, so every caller must make that one toast itself, from the " +
                "route's answer, and be checked here",
            (BottomSurfaces.keys + "MainActivity.kt").toSortedSet(),
            callers,
        )
        BottomSurfaces.forEach { (name, tappedDisplay) ->
            val lines = source(name)
            assertTrue(
                "$name: every quick-launch tap goes through the tap helpers, which make its one toast from the route's answer; " +
                    "a bare routeSecondaryQuickLaunch( or settleSecondaryQuickLaunchChoice( call leaves the toast to the surface, " +
                    "which only sees whether the tap was accepted (lines ${
                        lines.indices.filter {
                            lines[it].contains("routeSecondaryQuickLaunch(") || lines[it].contains("settleSecondaryQuickLaunchChoice(")
                        }.map { it + 1 }
                    })",
                lines.none { it.contains("routeSecondaryQuickLaunch(") || it.contains("settleSecondaryQuickLaunchChoice(") },
            )
            val request = lines.indexOfFirst {
                it.trim() == "val onPrimaryQuickLaunch: (String, Boolean) -> QuickLaunchPrimaryRouteResult = { packageName, fromGeneral ->"
            }
            assertTrue("$name: its Main request hands back the route's answer", request >= 0)
            assertEquals(
                "$name: that request is the route's own Quick launch dispatch",
                "CompanionQuickLaunchPrimaryRoute.dispatch(packageName, fromGeneral)",
                lines[request + 1].trim(),
            )
            val taps = lines.indices.filter {
                val trimmed = lines[it].trim()
                trimmed.startsWith("routeSecondaryQuickLaunchTap(") || trimmed.startsWith("settleSecondaryQuickLaunchChoiceTap(")
            }
            assertEquals("$name: its quick-launch taps", ExpectedTaps.getValue(name), taps.size)
            taps.forEach { start ->
                val close = (start + 1..lines.lastIndex).first {
                    lines[it].trim() == ")" && lines[it].indent() == lines[start].indent()
                }
                val arguments = lines.subList(start + 1, close).map { it.trim() }
                assertTrue(
                    "$name line ${start + 1}: sends the route's answer to the helper, arguments were $arguments",
                    "onPrimary = onPrimaryQuickLaunch," in arguments,
                )
                assertTrue(
                    "$name line ${start + 1}: its toast is made on the display the player tapped, arguments were $arguments",
                    "feedbackContext = $tappedDisplay," in arguments,
                )
                assertTrue(
                    "$name line ${start + 1}: keeps the surface's own copy for what the route did not explain, arguments were $arguments",
                    arguments.any { it.startsWith("unavailableCopy = ") },
                )
                val handler = (start - 1 downTo 0).first { lines[it].trim().endsWith("->") }
                val handlerEnd = (close + 1..lines.lastIndex).first { lines[it].trim().startsWith("}") }
                val ownToasts = (handler + 1 until handlerEnd)
                    .filter { it !in start..close }
                    .filter { lines[it].contains("Toast.makeText(") || lines[it].contains("showMimirMessage(") || lines[it].contains("showCompanionFeedbackToast(") }
                assertTrue(
                    "$name line ${start + 1}: the tap's handler (line ${handler + 1}) makes no toast besides the helper's, " +
                        "found one on lines ${ownToasts.map { it + 1 }}",
                    ownToasts.isEmpty(),
                )
            }
        }
        assertTrue(
            "the Bottom Quick launch window reads the view of its own window, on the Bottom",
            source("BottomQuickLaunchPanelHost.kt").any { it.trim() == "val panelView = LocalView.current" },
        )

        val main = source("MainActivity.kt")
        val dispatches = main.indices.filter { main[it].contains("CompanionQuickLaunchPrimaryRoute.dispatch(") }
        assertEquals("MainActivity makes one Main route request, SWAP's last leg", 1, dispatches.size)
        val dispatch = dispatches.single()
        assertEquals(
            "SWAP's last leg keeps the route's answer",
            "val mainRoute = CompanionQuickLaunchPrimaryRoute.dispatch(",
            main[dispatch].trim(),
        )
        val callClose = (dispatch + 1..main.lastIndex).first {
            main[it].trim() == ")" && main[it].indent() == main[dispatch].indent()
        }
        assertEquals("val accepted = mainRoute == QuickLaunchPrimaryRouteResult.Accepted", main[callClose + 1].trim())
        assertEquals("MainActivity line ${callClose + 3} checks the refusal", "if (!accepted) {", main[callClose + 2].trim())
        val refusal = callClose + 2
        val refusalEnd = (refusal + 1..main.lastIndex).first {
            main[it].trim() == "}" && main[it].indent() == main[refusal].indent()
        }
        val toasts = (refusal + 1 until refusalEnd).filter {
            main[it].contains("showCompanionFeedbackToast(") || main[it].contains("Toast.makeText(") || main[it].contains("showMimirMessage(")
        }
        assertEquals(
            "a refused last leg makes one toast, on the Bottom (showCompanionFeedbackToast), found lines ${toasts.map { it + 1 }}",
            1,
            toasts.size,
        )
        val toast = main[toasts.single()].trim()
        assertTrue(
            "SWAP's toast names the move the player asked for, not the route's reason: $toast",
            toast.startsWith("showCompanionFeedbackToast(") && "mainRoute" !in toast,
        )
    }

    private fun routeBody(): List<String> {
        val from = coordinator.indexOfFirst { it.trim().startsWith("val launchHomeQuickPackageOnPrimary: (") }
        check(from >= 0) { "launchHomeQuickPackageOnPrimary not found" }
        val to = (from + 1..coordinator.lastIndex).first {
            coordinator[it].trim().startsWith("val launchHomeQuickPackage: (String, Boolean) -> Boolean = launch@{")
        }
        return coordinator.subList(from + 1, to)
    }

    private fun List<String>.block(start: String, end: String): List<String> {
        val from = indexOfFirst { it.trim() == start }
        check(from >= 0) { "`$start` not found" }
        val to = (from + 1..lastIndex).first { this[it].trim().startsWith(end) }
        return subList(from + 1, to)
    }

    private fun String.indent(): Int = takeWhile { it == ' ' }.length

    private fun source(name: String): List<String> = File("$MainSourceDir/$name").readLines()

    private companion object {
        const val MainSourceDir = "src/main/kotlin/com/gruning/mimir/rewrite"

        /** Each Bottom surface that calls the route, with the context its taps' toasts are made from. */
        val BottomSurfaces = mapOf(
            // The overlay drawer's window is lent on the Bottom; its view's display is where the player tapped.
            "CompanionRecentsOverlayHost.kt" to "{ companionFeedbackContext(appContext, drawerView.display?.displayId) }",
            // O5's Bottom Quick launch window, lent on the Bottom the same way.
            "BottomQuickLaunchPanelHost.kt" to "{ companionFeedbackContext(appContext, panelView.display?.displayId) }",
            // The Presentation's own context is on the Bottom display.
            "MimirCompanionPresentation.kt" to "{ context }",
        )
        val ExpectedTaps = mapOf(
            "CompanionRecentsOverlayHost.kt" to 3,
            "BottomQuickLaunchPanelHost.kt" to 2,
            "MimirCompanionPresentation.kt" to 3,
        )
    }
}
