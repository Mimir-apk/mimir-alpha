package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the earlier update and the earlier update, read as source because neither the process runtime nor the overlay window can run in a unit test: the lines that
 * make the Bottom Utility window the only answer to the Bottom ᛗ's Utility, keep it touch-only and out of Main, and put the Main-copy
 * clearance in front of a plain Bottom launch and nowhere else.
 */
class BottomFixWiringPinTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).first(File::isFile).readText()

    private val main = "app/src/main/kotlin/com/gruning/mimir/rewrite"

    @Test
    fun `the Utility window is lent as a non-focusable companion panel and never reads a key or starts an Activity`() {
        val host = source("$main/BottomUtilityPanelHost.kt")
        assertTrue("it is the companion's panel, on the Bottom", host.contains("overlay.attachSecondaryPanel(panelRoot)"))
        assertFalse("never the primary's focusable panel", host.contains("attachPrimaryPanel"))
        for (forbidden in listOf(
            "KEYCODE_BACK",
            "dispatchKeyEvent",
            "onKeyEvent",
            "onPreviewKeyEvent",
            "KeyEvent",
            ".focusable(",
            "focusTarget(",
            "startActivity",
            "MainActivity",
            "sendKeyEvent",
        )) {
            assertFalse("the Bottom Utility window must not use $forbidden", host.contains(forbidden))
        }
        assertTrue("the panel's Back is a touch Back: no BackHandler", source("$main/AppBrightnessCoordinator.kt").contains("enableBackHandler = target == AppBrightnessPresentationTarget.PRIMARY,"))
    }

    @Test
    fun `the router answers the Bottom's Utility before the global mapping, and never reads which window is on top`() {
        val runtime = source("$main/MButtonRuntime.kt")
        val router = runtime.substringAfter("internal class MButtonProcessActionRouter(")
        val utility = router.indexOf("bottomUtilityRoute(menu.role, menu.item)")
        val global = router.indexOf("val globalCommand = menu?.item?.let(::globalMButtonCommand)")
        assertTrue("the Bottom's Utility is answered: $utility", utility >= 0)
        assertTrue("and before the global mapping would start MainActivity: $utility < $global", utility in 0 until global)
        val branch = router.substring(utility, router.indexOf("bottomQuickLaunchRoute(menu.role, menu.item)"))
        assertFalse("the route does not ask which window is on top", branch.contains("disposition") || branch.contains("MirrorPresented"))
        assertFalse("and does not read the runtime snapshot", branch.contains("CompanionReturnCoordinator"))
        assertFalse("the old covered-Presentation fallback is gone", runtime.contains("secondaryPresentationShowing") || runtime.contains("SecondaryUtilityRoute"))
        assertFalse(
            "Utility never falls back to Main: no Main fallback helper and no primary dispatch of Utility from the Bottom route",
            runtime.contains("bottomUtilityMayUseMain") || branch.contains("completePrimaryDispatch") || branch.contains("MButtonPrimaryCommand.UTILITY"),
        )
    }

    @Test
    fun `the Presentation hands the instruments layer to the window and the process wires one host`() {
        val presentation = source("$main/MimirCompanionPresentation.kt")
        assertTrue(presentation.contains("CompanionProcessRuntime.services(context).bottomUtilityPanel }"))
        assertTrue(presentation.contains("bottomUtilityPanel.covering"))
        assertTrue(presentation.contains("bottomInstrumentsPanelShownInPresentation(secondaryTweakerVisible, bottomUtilityCovering) -> CompanionInstrumentPanelKind.TWEAKER"))
        assertTrue("the rails' seat still reads the model's own state", presentation.contains("instrumentsShown = secondaryTweakerVisible,"))

        val runtime = source("$main/CompanionProcessRuntime.kt")
        assertEquals(1, Regex("""BottomUtilityPanelHost\(""").findAll(runtime).count())
        assertTrue(runtime.contains("bottomUtility = bottomUtilityPanel,"))
        assertTrue(runtime.contains("bottomUtilityPanel = bottomUtilityPanel,"))
        assertTrue("the Quick Launch wiring is untouched", runtime.contains("bottomQuickLaunch = bottomQuickLaunchPanel,"))
    }

    @Test
    fun `the stale claim about where the dual-screen emulators draw is corrected`() {
        val mainActivity = source("$main/MainActivity.kt")
        assertFalse(mainActivity.contains("melonDS/Azahar render BOTH DS\n"))
        assertFalse("the claim is no longer made as a reason", mainActivity.contains("screens on the PRIMARY, so no foreign window"))
        assertTrue(mainActivity.contains("Corrected 2026-10-09"))
    }

    @Test
    fun `the Main-copy clearance runs on the launch's work stage for Quick Launch only, and the door is scoped to it`() {
        val runtime = source("$main/CompanionProcessRuntime.kt")
        val hook = runtime.substringAfter("beforeDispatch = { plan ->").substringBefore("CompanionRecentsState.setPreparedLaunchHandler")
        assertTrue("only Quick Launch's plan", hook.contains("plan.clearMainCopy &&"))
        assertTrue("and only a plain app", hook.contains("plan.launchMode == com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_APP"))
        assertTrue("the package is admitted for the clearance alone", hook.contains("BottomLaunchAdmission.admitting(plan.packageName)"))
        assertTrue("every package in the emulator registry is spared", hook.contains("emulatorTarget = isRegisteredEmulatorPackage(plan.packageName)"))
        assertFalse("not just the ones the force-stop door admits", hook.contains("MimirLaunchTargetStopPolicy.isEmulatorTarget"))
        assertTrue("a game session's package is passed", hook.contains("gamePackages = setOfNotNull(game?.targetPackageName)"))
        assertTrue("through the verified close authority", hook.contains("gateway = verifiedCloseAuthority.get(),"))
        assertTrue("a replaced launch removes nothing", hook.contains("stillWanted = { planIsCurrent(plan) },"))
        assertTrue("the launch's own deadline bounds it", hook.contains("deadlineMs = bottomMainCopyDeadlineMs(plan.plannedAtMs),"))
        assertTrue("a launch that parks a Main game is not cleared", hook.contains("if (mainGameToPark() != null)"))
        assertTrue("and the launch reads the very same answer", runtime.substringAfter("launchBottomAppPlan = launchBottomAppPlan@").contains("val activeGame = mainGameToPark()"))
        assertEquals("one definition of what parks", 1, Regex("""fun mainGameToPark\(""").findAll(runtime).count())
        assertTrue("Mimir comes forward before the removal, via O12's step", hook.contains("bringCompanionMainForward(context, CompanionExchangeInFlight.active)"))
        assertTrue("never over a game on Main", hook.contains("if (game?.placement == MimirGamePlacement.MAIN) {"))

        val coordinator = source("$main/CompanionSecondaryLaunchCoordinator.kt")
        val work = coordinator.substringAfter("workScheduler.schedule {").substringBefore("mainDispatcher.dispatch dispatch@{")
        val persist = work.indexOf("persistPlan(plan)")
        val clear = work.indexOf("runCatching { beforeDispatch(plan) }")
        assertTrue("after the plan is saved, before the launch returns to main: $persist < $clear", persist in 0 until clear)
        assertEquals("the game path that waits for its host does not run it", 1, Regex("""beforeDispatch\(plan\)""").findAll(coordinator).count())

        val clearance = source("$main/BottomMainCopyClearance.kt")
        assertTrue("it removes by id only", clearance.contains("authority.removeTask(taskId)"))
        assertTrue("every gateway call waits only as long as the deadline allows", clearance.contains("calls.call(room)") && clearance.contains("calls.call(removeRoom)"))
        assertFalse("it never stops a package", clearance.contains("closeLaunchTarget"))
        assertFalse("and never launches", clearance.contains("startActivity"))
    }

    @Test
    fun `only the three Quick Launch callers ask for the clearance, and every other way onto the Bottom launches as before`() {
        // Quick Launch's picker window, the Presentation's dock and the Home picker.
        val quickLaunch = listOf(
            "$main/BottomQuickLaunchPanelHost.kt" to "CompanionRecentsState.onLaunch(packageName, label, clearMainCopy = true)",
            "$main/MimirCompanionPresentation.kt" to "CompanionRecentsState.onLaunch(packageName, label, clearMainCopy = true)",
            "$main/MimirAppCoordinator.kt" to "CompanionRecentsState.onLaunch(packageName, label, clearMainCopy = true)",
        )
        for ((file, call) in quickLaunch) {
            assertEquals("$file asks exactly once", 1, Regex(Regex.escape(call)).findAll(source(file)).count())
        }
        // The tiles, the recent rail, a tab re-tap, the HOME-park resume and the debug hook: none sets it.
        val everyFile = listOf(
            "$main/MimirCompanionPresentation.kt",
            "$main/CompanionRecentsOverlayHost.kt",
            "$main/HomeParkDualScreen.kt",
            "$main/MainActivity.kt",
            "$main/BottomQuickLaunchPanelHost.kt",
            "$main/MimirAppCoordinator.kt",
        )
        val asking = everyFile.sumOf { file -> Regex("""clearMainCopy\s*=\s*true""").findAll(source(file)).count() }
        assertEquals("three callers in all, and nowhere else in the launch callers", 3, asking)
        assertFalse(source("$main/CompanionRecentsOverlayHost.kt").contains("clearMainCopy"))
        assertFalse(source("$main/HomeParkDualScreen.kt").contains("clearMainCopy"))

        val runtime = source("$main/CompanionProcessRuntime.kt")
        assertTrue(
            "the flag reaches the coordinator through the handler",
            runtime.contains("secondaryLaunchCoordinator.prepare(packageName, clearMainCopy)"),
        )
    }

    @Test
    fun `the Tweaker's shelf and Tabs seats open the one Bottom window, and nothing else draws a second one`() {
        val presentation = source("$main/MimirCompanionPresentation.kt")
        assertEquals("the shelf seat and the drawer's seat", 2, Regex("""bottomUtilityPanel\.open\(UtilitySurface\.TWEAKER\)""").findAll(presentation).count())
        assertFalse(
            "neither opens the model inside the Presentation any longer",
            Regex("""sharedBrightnessCoordinator\?\.open\(""").containsMatchIn(presentation),
        )
        val overlayHost = source("$main/CompanionRecentsOverlayHost.kt")
        assertTrue("the overlay-hosted drawer's seat opens the same window", overlayHost.contains("bottomUtilityPanel.open(UtilitySurface.TWEAKER)"))
        assertTrue("with the emulator's screen not showing through the drawer over a game", overlayHost.contains("opaqueBackdrop = dualScreenGameLive"))
        val runtime = source("$main/CompanionProcessRuntime.kt")
        assertEquals("one host, built once", 1, Regex("""BottomUtilityPanelHost\(""").findAll(runtime).count())
    }

    @Test
    fun `a dual-screen game moves the Tabs drawer to the overlay window at every place the host is chosen`() {
        val activity = source("$main/MainActivity.kt")
        assertEquals(
            "the eligibility check, the Tabs request and the reconcile",
            3,
            Regex("""dualScreenGameLive\s*=\s*companionMainDualScreenGameActive\(MimirSessionAuthority\.state\.value\.gameSession\)""").findAll(activity).count(),
        )
    }

    @Test
    fun `the debug launch hook in MainActivity runs only behind the debug-build gate`() {
        val activity = source("$main/MainActivity.kt")
        assertTrue(activity.contains("private val debugIntentHookGate = DebugIntentHookGate(BuildConfig.DEBUG)"))
        assertTrue(activity.contains("private fun runDebugRecentsIntentHook() = debugIntentHookGate.run {"))
        assertEquals("maybeShowRecents has no caller but the gated one", 1, Regex("""\bmaybeShowRecents\(\)\s*\n\s*\}""").findAll(activity).count())
        assertEquals("the declaration and the one call", 2, Regex("""maybeShowRecents\(\)""").findAll(activity).count())
    }

    @Test
    fun `the Files row follows the model flag at its rail, its focus order and its press`() {
        val coordinator = source("$main/AppBrightnessCoordinator.kt")
        assertEquals("the focus order, the dispatcher and the panel host", 3, Regex("""filesRouteOffered""").findAll(coordinator).count() - 1)
        assertFalse("no read of the host flag is left beside them", Regex("""filesRouteAvailable\s*=\s*(coordinator\.)?hostsInAppSurfaces""").containsMatchIn(coordinator))
        // The press decides on all three facts by name (R2's own arm, combined43 F1): the host gate, the access snapshot and the ledger mirror
        // it has just refreshed. A call that drops `gameRunning` is the old two-way decision, which read the withdrawn row as "no host".
        assertTrue(Regex("""filesRouteDecision\(hostsInAppSurfaces, allFilesAccessGranted, gameRunning = gameRunning\)""").containsMatchIn(coordinator))
        assertFalse(Regex("""filesRouteDecision\(filesRouteOffered""").containsMatchIn(coordinator))
    }
}
