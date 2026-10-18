package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O5 and D3 run through window and Activity-lifecycle code no JVM test can drive. The decisions are tested as behaviour
 * in [BottomQuickLaunchRouteTest] and [MButtonCommandResumeTest]; these pins keep the product calling them.
 */
class MButtonCommandResumeWiringTest {

    private fun source(name: String): String {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/$name")
        assertTrue("$name must be readable (${file.absolutePath})", file.isFile)
        return file.readText()
    }

    @Test
    fun `MainActivity's command delivery notes the command before the resume it causes`() {
        val delivery = source("MainActivity.kt")
            .substringAfter("private fun consumeMButtonCommandIntent(", "")
            .substringBefore("private fun runDebugIntentHooks()", "")
        assertTrue(
            "onCreate and onNewIntent hand the intent to the authority",
            delivery.contains("MButtonIntentAuthority.consume(intent)"),
        )
        val consume = source("MButtonRuntime.kt")
            .substringAfter("internal object MButtonIntentAuthority {", "")
            .substringAfter("fun consume(", "")
            .substringBefore("internal object MButtonNavigationCommands", "")
        assertTrue(
            "by default the delivery is noted in the process ledger the resume reads",
            consume.contains("noteDelivered: () -> Unit = PrimaryActivityObservationState::noteMButtonCommandDelivered"),
        )
        val recognised = consume.indexOf("?: return null")
        val noted = consume.indexOf("noteDelivered()")
        assertTrue("only a token this process issued is noted", recognised in 0 until noted)
    }

    @Test
    fun `onResume starts a game return only from the observation's ticket`() {
        val resume = source("MainActivity.kt")
            .substringAfter("override fun onResume() {", "")
            .substringBefore("private fun settleReturningGameSession(", "")
        assertTrue(resume.contains("val resumeObservation = PrimaryActivityObservationState.onPrimaryResumed()"))
        assertTrue(resume.contains("val stopTicket = resumeObservation.returnTicket"))
        assertTrue(resume.contains("stopTicket.gameOperation?.let { operation ->"))
        assertTrue(resume.contains("shouldSettleTweakerAfterPrimaryResume(stopTicket)"))
        assertEquals(
            "onResume has exactly one game-return start",
            1,
            Regex("""settleReturningGameSession\(""").findAll(resume).count(),
        )
    }

    @Test
    fun `a skipped return leaves its line on the session tag`() {
        assertEquals(
            "game return skipped reason=m_button_command op=home:zelda game=YIELDED_TO_SECONDARY_TASK",
            primaryResumeGameReturnWithheldLogMessage(
                MimirGameSessionOperation("home:zelda"),
                MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
            ),
        )
        val resumed = source("CompanionProcessRuntime.kt")
            .substringAfter("fun onPrimaryResumed(): PrimaryResumeObservation {", "")
            .substringBefore("fun onPrimaryStopped(", "")
        assertTrue(resumed.contains("observation.withheldGameOperation?.let"))
        assertTrue(resumed.contains("\"MimirSession\","))
    }

    @Test
    fun `Home reads the same hold before it treats a resume as the launched game's return`() {
        val home = source("HomeShelfCoordinator.kt")
        val guard = "if (PrimaryActivityObservationState.gameReturnWithheldByMButtonCommand())"
        val cold = home.substringAfter("LaunchedEffect(persistedLaunchReturn?.token) {", "")
        assertTrue(
            "the cold finish checks the hold before finishing the elapsed token",
            cold.indexOf(guard) in 0 until cold.indexOf("HomeExternalSessionElapsedStore.finalize("),
        )
        val resumed = home.substringAfter("override fun onResume(owner: LifecycleOwner) {", "")
        assertTrue(
            "the resume observer checks the hold before it reads the launch-return record",
            resumed.indexOf(guard) in 0 until resumed.indexOf("HomeLaunchReturnStore.peek(context)"),
        )
    }

    @Test
    fun `the Bottom picker is built by the process runtime, routed to, and reports its touches`() {
        val runtime = source("CompanionProcessRuntime.kt")
        assertTrue(runtime.contains("bottomQuickLaunch = bottomQuickLaunchPanel,"))
        assertTrue(runtime.contains("bottomQuickLaunchPanel = bottomQuickLaunchPanel,"))

        val host = source("BottomQuickLaunchPanelHost.kt")
        assertTrue("the picker window is the root that sees every touch", host.contains("overlay.attachSecondaryPanel(panelRoot)"))
        assertTrue(
            "and its touches count for Main's screen idle (round-5 B2)",
            host.contains(
                "overlayScreenActivityFor(event.actionMasked)?.let(this@BottomQuickLaunchPanelHost.overlay::notifyScreenActivity)",
            ),
        )
        assertTrue("it hosts the Bottom's own picker without a second rail", host.contains("railVisible = false,"))
        assertTrue(host.contains("generalPickerRequest = request,"))
        assertFalse("and has no route to MainActivity", host.contains("MButtonIntentAuthority"))

        val presentation = source("MimirCompanionPresentation.kt")
        assertTrue(presentation.contains("CompanionProcessRuntime.services(context).bottomQuickLaunchPanel.covering"))
        assertTrue(
            "the Presentation leaves a pending choice to the window drawing it",
            presentation.contains("val quickLaunchChoice = pendingQuickLaunchChoice.takeUnless { bottomQuickLaunchCovering }"),
        )
    }
}
