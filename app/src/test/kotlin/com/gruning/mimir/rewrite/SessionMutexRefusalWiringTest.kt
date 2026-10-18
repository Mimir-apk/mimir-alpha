package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import com.gruning.mimir.rewrite.feature.library.GameLaunchSessionHold
import com.gruning.mimir.rewrite.feature.library.GameLaunchUiState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Outcome-dropping audit finding O-3 (2026-08-12): the session-mutex refusal — a launch turned away
 * because another launch already holds the session — was dropped silently or landed on the actively-wrong
 * [com.gruning.mimir.rewrite.feature.library.GameLaunchUiState.Idle] at every site that produces it.
 *
 * [emitHomeShelfSessionHeldMessage] is the one pure decision this fix introduces in `app/` and is tested
 * behaviourally below. The call sites themselves (two Home shelf row-tap paths, the Home launch card,
 * Quick Launch, the native shelf, and the Bottom card's game launch) live inside `@Composable` closures no JVM test
 * can drive — the same gap this module's own `AppBrightnessCoordinatorDeclineFeedbackTest` and
 * `HomeShelfLaunchFailureMessageTest` document and accept for the identical reason. Each is pinned here as a
 * source-characterization test instead: it greps the exact production lines, so reverting the wiring (not just the
 * pure helper) turns it red too.
 *
 * O15 (policy 2026-09-12, "Split: starting vs running"): each refusal now says which. The admission call that
 * refused the launch reports what held it (`onHeld = { heldBy = it }`, read from the snapshot the refusal was decided
 * on), and the site hands exactly that report to feature-library's selector. A site that names a hold of its own, or
 * drops the report, fails here; a site that writes its own words fails `LauncherCopyContractSourcePinTest`. The behaviour
 * of the report itself is `MimirSessionLaunchHoldTest`'s.
 *
 * O15 follow-up (coordinator review, 2026-09-12): the Main quick-launch route's guard on the Library's own launch asks the
 * ledger what that launch state means now ([libraryLaunchGuardHold], behaviour in `LibraryLaunchGuardTest`); the Bottom
 * card's launch verb reaches admission instead of a quiet refusal in the bridge ([companionCommandAnswer], behaviour in
 * `CompanionCommandAnswerTest`); and the card's refusal is made on the Bottom ([showCompanionPickedGameRefusal], behaviour
 * in `CompanionPickedGameRefusalTest`). The wiring of each is pinned here.
 */
class SessionMutexRefusalWiringTest {

    /**
     * The third consuming renderer (Library detail card and Home launch card are pinned in
     * `feature-library`'s `GameLaunchSessionHeldTest`): the companion payload's short status label
     * ([companionLaunchStatusLabel]), which O15 splits the same way as the sentence. Nothing paints it today, and
     * [thePayloadStatusLabelHasNoProductionReaderToday] keeps that claim true.
     */
    @Test
    fun companionPayloadStatusLabelSplitsForEachHold() {
        assertEquals(
            "Launch busy",
            companionLaunchStatusLabel(GameLaunchUiState.SessionHeld(GameLaunchSessionHold.STARTING)),
        )
        assertEquals(
            "Still open",
            companionLaunchStatusLabel(GameLaunchUiState.SessionHeld(GameLaunchSessionHold.RUNNING)),
        )
        assertNotEquals(
            companionLaunchStatusLabel(GameLaunchUiState.Idle),
            companionLaunchStatusLabel(GameLaunchUiState.SessionHeld(GameLaunchSessionHold.STARTING)),
        )
        assertNull(companionLaunchStatusLabel(GameLaunchUiState.Idle))
    }

    /**
     * `CompanionPayload.statusLabel` is written (`CompanionPayloadAdapter`) and cleared (`CompanionState`), and no production
     * code reads it, so neither short label reaches a screen. If a surface starts reading it, this fails: its words would
     * then be copy a player sees, which O15 did not rule.
     */
    @Test
    fun thePayloadStatusLabelHasNoProductionReaderToday() {
        val root = repoRoot()
        val readers = productionKotlinFiles(root).flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.trim()
                val comment = code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")
                if (!comment && StatusLabelRead.containsMatchIn(line)) {
                    "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1}  $code"
                } else {
                    null
                }
            }
        }
        assertEquals(
            "production code reads the payload's status label, so its words are now painted:\n" + readers.joinToString("\n"),
            emptyList<String>(),
            readers,
        )
    }

    @Test
    fun theSharedHelperEmitsTheWarningSentenceForTheHoldItIsGiven() {
        val calls = mutableListOf<Pair<MimirMessageClass, String>>()
        emitHomeShelfSessionHeldMessage(GameLaunchSessionHold.STARTING) { messageClass, text ->
            calls.add(messageClass to text)
        }
        emitHomeShelfSessionHeldMessage(GameLaunchSessionHold.RUNNING) { messageClass, text ->
            calls.add(messageClass to text)
        }

        assertEquals(
            listOf(
                MimirMessageClass.Warning to
                    "Mimir is already launching something else. Wait for it to finish, then try again.",
                MimirMessageClass.Warning to "A game or app is still open. Close it first, then try again.",
            ),
            calls,
        )
    }

    @Test
    fun homeShelfRowTapEmulatedBranchHandsTheRefusalsHoldToTheSharedHelper() {
        // Anchored on the row-tap `launch:` lambda itself (`launchAction@{`), not the unrelated
        // `setMembershipById` mutation `when` earlier in the file, which reuses the same case labels.
        val rowTapLambda = homeShelfCoordinatorSource().sectionAfter("val launch: (HomeShelfRow) -> Unit = launchAction@{")
        val emulatedBranch = rowTapLambda.sectionAfter("is HomeShelfItemId.Emulated -> {")
            .substringBefore("is HomeShelfItemId.AndroidGame -> {")

        // The row now retries
        // admission once after releasing a stale parked lease, and on a refusal that stands hands the
        // hold to the parked-aware helper (still `gameLaunchSessionHeldMessage` for every other hold;
        // see `emitHomeShelfGameRefusalMessage`'s own KDoc).
        assertRefusalHandsOnTheAdmissionsHold(
            site = "the Emulated row tap",
            section = emulatedBranch,
            admission = "MimirSessionAuthority.prepareHomeGame(",
            refusal = "operation ?: run {",
            expectedUse = "emitHomeShelfGameRefusalMessage(heldBy, emitMessage)",
        )
    }

    @Test
    fun homeShelfRowTapAndroidGameBranchHandsTheRefusalsHoldToTheSharedHelper() {
        val rowTapLambda = homeShelfCoordinatorSource().sectionAfter("val launch: (HomeShelfRow) -> Unit = launchAction@{")
        val androidGameBranch = rowTapLambda.sectionAfter("is HomeShelfItemId.AndroidGame -> {")
            .substringBefore("is HomeShelfItemId.AndroidApp -> Unit // Retired Home identity")

        assertRefusalHandsOnTheAdmissionsHold(
            site = "the AndroidGame row tap",
            section = androidGameBranch,
            admission = "MimirSessionAuthority.preparePrimaryApp(",
            refusal = ") ?: run {",
            expectedUse = "emitHomeShelfSessionHeldMessage(heldBy, emitMessage)",
        )
    }

    @Test
    fun homeLaunchCardMutexRefusalSetsSessionHeldWithTheAdmissionsHoldNotIdle() {
        // Anchored on the lambda body itself, not the earlier `val launchFromLaunchCard: (...)` TYPE
        // declaration on the HomeShelfCoordinator data class, which shares the same leading text.
        val launchCardFn = homeShelfCoordinatorSource()
            .sectionAfter("launchCard@{ row, game, onLaunchState, onDispatched, choice ->")
            .substringBefore("return HomeShelfCoordinator(")

        // The card now retries
        // admission once after releasing a stale parked lease (bound to `admittedOperation` since
        // `operation` is a captured `var`), and on a refusal that stands still sets SessionHeld exactly
        // as before -- a still-parked refusal additionally gets Main's one-line toast (see the assertion
        // below), never a card status line.
        assertRefusalHandsOnTheAdmissionsHold(
            site = "the Home launch card",
            section = launchCardFn,
            admission = "MimirSessionAuthority.prepareHomeGame(",
            refusal = "if (admittedOperation == null) {",
            expectedUse = "onLaunchState(GameLaunchUiState.SessionHeld(heldBy))",
        )
        assertFalse(
            "launchFromLaunchCard's mutex refusal must never go back to the actively-wrong Idle",
            launchCardFn.contains("onLaunchState(GameLaunchUiState.Idle)"),
        )
    }

    @Test
    fun quickLaunchPrimaryPathAnswersTheRefusalWithTheSentenceForItsHold() {
        assertRefusalHandsOnTheAdmissionsHold(
            site = "Quick launch's Main route",
            section = quickLaunchPrimaryBody(),
            admission = "MimirSessionAuthority.preparePrimaryApp(",
            refusal = ") ?: run {",
            expectedUse = "return@launch refused(gameLaunchSessionHeldMessage(heldBy))",
        )
    }

    /**
     * O15 follow-up: the route's guard reads the Library's launch state only through [libraryLaunchGuardHold], before
     * admission. Still starting keeps the guard's words; already running gets the running-state sentence; anything else goes on to
     * admission.
     */
    @Test
    fun quickLaunchRouteAsksTheLedgerWhatTheLibrarysLaunchStateMeansBeforeAdmission() {
        val body = quickLaunchPrimaryBody()
        val lines = body.lines().map(String::trim)
        val guard = lines.indexOf(
            "val libraryLaunchHold = libraryLaunchGuardHold(library.launchState, MimirSessionAuthority.state.value)",
        )
        assertTrue("the route reads the Library's launch state through the ledger's answer", guard >= 0)
        assertEquals(
            listOf(
                "if (libraryLaunchHold == GameLaunchSessionHold.STARTING) {",
                "return@launch refused(\"Still opening the previous app\")",
                "}",
                "if (libraryLaunchHold == GameLaunchSessionHold.RUNNING) {",
                "return@launch refused(gameLaunchSessionHeldMessage(libraryLaunchHold))",
                "}",
            ),
            lines.subList(guard + 1, guard + 7),
        )
        assertEquals(
            "the Library's launch state is read nowhere else in the route",
            1,
            lines.count { it.contains("library.launchState") },
        )
        assertTrue(
            "the guard answers before admission",
            body.indexOf("libraryLaunchGuardHold(") < body.indexOf("MimirSessionAuthority.preparePrimaryApp("),
        )
    }

    @Test
    fun quickLaunchPrimaryPathAdmitsBeforeResolverIoAndUsesProcessLifetime() {
        val body = quickLaunchPrimaryBody()
        val admission = body.indexOf("MimirSessionAuthority.preparePrimaryApp(")
        val resolver = body.indexOf("resolveNativeShelfLaunchIntent(appContext, cleanPackageName)")

        assertTrue("primary quick launch must admit before resolver I/O", admission >= 0 && admission < resolver)
        assertTrue(
            "an admitted quick launch must use process lifetime so Activity recreation cannot cancel it",
            body.contains("CompanionProcessRuntime.launchPrimaryQuickLaunch"),
        )
        assertFalse(
            "an admitted quick launch must not run in the Activity-owned rememberCoroutineScope",
            body.contains("homeQuickLaunchScope.launch"),
        )
    }

    @Test
    fun quickLaunchPrimaryPinsNativeAppDispatchToTheDefaultDisplay() {
        val body = quickLaunchPrimaryBody()
        val start = body.indexOf("startNativeShelfLaunchIntent(")
        assertTrue("primary quick launch must use the display-targeted native start seam", start >= 0)
        val call = body.substring(start).substringBefore("\n                    )")
        assertTrue(
            "saved MAIN and General routes share an explicit primary display target",
            call.contains("launchDisplayId = Display.DEFAULT_DISPLAY"),
        )
    }

    @Test
    fun quickLaunchPrimaryResolverFailureSettlesTheAdmittedSession() {
        val failure = quickLaunchPrimaryBody()
            .substringAfter("if (intent == null) {")
            .substringBefore("return@withContext")

        assertTrue(
            "a missing launch intent must fail the exact admitted primary app operation",
            failure.contains("MimirSessionAuthority.appFailed(operation, \"no_launchable_intent\")"),
        )
        assertTrue(
            "resolver failure must not advance General MRU",
            failure.contains("dispatchSucceeded = false"),
        )
        assertTrue(
            "resolver failure must remain visible",
            failure.contains("showMimirMessage(") && failure.contains("\"App couldn’t be launched\","),
        )
    }

    @Test
    fun nativeShelfLaunchPackageToastsTheSentenceForTheRefusalsHold() {
        assertRefusalHandsOnTheAdmissionsHold(
            site = "the native shelf",
            section = mimirNativeShelfCoordinatorSource(),
            admission = "val operation = MimirSessionAuthority.preparePrimaryApp(",
            refusal = ") ?: run {",
            expectedUse = "showMimirMessage(context, gameLaunchSessionHeldMessage(heldBy))",
        )
    }

    /**
     * O15 follow-up: the Bottom card's refused launch is told what held it on the Bottom, from the process context and the
     * selected secondary display, and leaves nothing behind: the opening intro the card marked as launching is cleared.
     */
    @Test
    fun bottomCardGameLaunchIsToldWhatHeldItOnTheBottomWithItsOpeningIntroCleared() {
        assertRefusalHandsOnTheAdmissionsHold(
            site = "the Bottom card's game launch",
            section = mimirAppCoordinatorSource(),
            admission = "val operation = MimirSessionAuthority.prepareHomeGame(",
            refusal = ") ?: run {",
            expectedUse = "hold = heldBy,",
        )
        val refusal = mimirAppCoordinatorSource()
            .sectionAfter("val operation = MimirSessionAuthority.prepareHomeGame(")
            .sectionAfter(") ?: run {")
            .substringBefore("\n    }")
            .lines()
            .map(String::trim)
        listOf(
            "showCompanionPickedGameRefusal(",
            "base = context.applicationContext,",
            "bottomDisplayId = CompanionProcessRuntime.services(context).secondaryDisplaySelector.snapshot.value.selectedId,",
            "gameIdValue = gameIdValue,",
            "GameOpeningIntroState.clearLaunching(gameIdValue)",
            "dismissGameOpeningPresentation(finishScope, \"rejected\")",
            "return",
        ).forEach { expected ->
            assertTrue(
                "the Bottom card's refusal must keep `$expected`, was:\n${refusal.joinToString("\n")}",
                expected in refusal,
            )
        }
        assertFalse(
            "the refusal makes no toast of its own, which Main's Activity context would put on Main",
            refusal.any { it.contains("Toast.makeText(") || it.contains("showMimirMessage(") },
        )
    }

    /**
     * O15 follow-up: the bridge does what [companionCommandAnswer] says. The launch verb has no refusal of its own and goes on
     * to the launch path; the console tap keeps its quiet refusal, which now lives in the answer.
     */
    @Test
    fun theBottomCardsLaunchVerbReachesAdmissionWhileTheConsoleTapKeepsItsQuietRefusal() {
        val lines = sourceFile("CompanionCommandBridge.kt").lines().map(String::trim)
        assertTrue(
            "the bridge asks the one answer for every command",
            "val answer = companionCommandAnswer(command, launchInFlight = inFlight, libraryForeground = onLibrary)" in lines,
        )
        val launch = lines.indexOf("is CompanionCommandState.Command.LaunchGameOnMain -> {")
        assertTrue("the launch verb is handled", launch >= 0)
        assertEquals(
            "the launch verb does what the answer says and nothing else",
            listOf(
                "if (answer != CompanionCommandAnswer.LAUNCH_GAME) return@collect",
                "onLaunchGame(command.gameIdValue, command.destination)",
            ),
            lines.drop(launch + 1).takeWhile { it != "}" }.filterNot { it.startsWith("//") },
        )
        assertTrue(
            "the console tap does what the answer says",
            "if (answer != CompanionCommandAnswer.OPEN_GAMELIST) return@collect" in lines,
        )
        assertFalse(
            "no refusal is decided in the bridge's body itself",
            lines.any { it.startsWith("if (inFlight") || it.startsWith("if (!onLibrary") },
        )
    }

    @Test
    fun theLibraryIsAnsweredWithTheHoldTheLedgerRecordedForItsOwnRefusedLaunch() {
        val lines = sourceFile("MimirSessionCoordinator.kt").lines().map(String::trim)
        assertTrue(
            "the authority records what held the Library launch it refuses",
            lines.any { it == "is LibraryGameSessionEvent.Preparing -> coordinator.gamePreparing(event) { hold ->" } &&
                lines.any { it == "libraryRefusals.record(event.operation, hold)" },
        )
        assertTrue(
            "and answers the Library's question from that record, for that exact operation",
            lines.any { it == "libraryRefusals.holdFor(event.operation)" },
        )
    }

    /**
     * The admission call in [section] must report what held a refusal into `heldBy`, and the refusal block after it (up to
     * its first `}`) must hand that report on through [expectedUse] and name no hold of its own.
     */
    private fun assertRefusalHandsOnTheAdmissionsHold(
        site: String,
        section: String,
        admission: String,
        refusal: String,
        expectedUse: String,
    ) {
        val fromAdmission = section.substringAfter(admission, "")
        assertTrue("$site: the admission call `$admission` was not found", fromAdmission.isNotEmpty())
        val admissionCall = fromAdmission.substringBefore(refusal, "")
        assertTrue("$site: no refusal `$refusal` follows the admission", admissionCall.isNotEmpty())
        assertTrue(
            "$site: the admission must report what held a refusal (`onHeld = { heldBy = it }`), was:\n$admissionCall",
            admissionCall.contains("onHeld = { heldBy = it }"),
        )
        val refusalBlock = fromAdmission.substringAfter(refusal).substringBefore("}")
        assertTrue(
            "$site: the refusal must hand the admission's report to the selector (`$expectedUse`), was:\n$refusalBlock",
            refusalBlock.contains(expectedUse),
        )
        assertFalse(
            "$site: the refusal names a hold of its own instead of the admission's report:\n$refusalBlock",
            refusalBlock.contains("GameLaunchSessionHold."),
        )
    }

    /** The text after [anchor]; an anchor that no longer matches fails instead of quietly searching the whole file. */
    private fun String.sectionAfter(anchor: String): String =
        substringAfter(anchor, "").also { check(it.isNotEmpty()) { "anchor not found: $anchor" } }

    private fun homeShelfCoordinatorSource(): String = sourceFile("HomeShelfCoordinator.kt")
    private fun mimirAppCoordinatorSource(): String = sourceFile("MimirAppCoordinator.kt")
    private fun mimirNativeShelfCoordinatorSource(): String = sourceFile("MimirNativeShelfCoordinator.kt")

    private fun quickLaunchPrimaryBody(): String = mimirAppCoordinatorSource()
        .substringAfter("val launchHomeQuickPackageOnPrimary: (String, Boolean, CompanionOverlayRole, Boolean) -> QuickLaunchPrimaryRouteResult = launch@{", "")
        .substringBefore("val launchHomeQuickPackage: (String, Boolean) -> Boolean = launch@{", "")
        .also { check(it.isNotEmpty()) { "launchHomeQuickPackageOnPrimary's body was not found in MimirAppCoordinator.kt" } }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    private fun repoRoot(): File = listOf(File(".."), File("."), File("../.."))
        .map(File::getCanonicalFile)
        .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "app/src/main").isDirectory }
        ?: error("repo root not found from ${File(".").absolutePath}")

    /** Every root-build module's production Kotlin (`<module>/src/main/kotlin`). */
    private fun productionKotlinFiles(root: File): List<File> {
        val files = root.listFiles { file: File -> file.isDirectory }.orEmpty()
            .map { File(it, "src/main/kotlin") }
            .filter(File::isDirectory)
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        check(files.size > 300) { "scanned only ${files.size} production .kt files -- the walk is broken, not the tree clean" }
        return files
    }

    private companion object {
        /** A read of a property named statusLabel: `.statusLabel` or a `::statusLabel` reference. */
        val StatusLabelRead = Regex("""(\.|::)statusLabel\b""")
    }
}
