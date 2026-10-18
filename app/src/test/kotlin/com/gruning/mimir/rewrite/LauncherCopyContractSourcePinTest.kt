package com.gruning.mimir.rewrite

import java.io.File
import java.util.Arrays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Launcher copy contracts pinned in production source.
 *
 * The TABS drawer matches its coin. Where-it-opens labels use "Opens on", retaining each register:
 * `ᛗ · OPENS ON` in headers, `Opens on` in rows, and `… where it opens` in sentences.
 * Player-visible failures use "Couldn't …", including mid-sentence contractions. Log arguments and
 * the explicitly justified `CouldNotNeverShown` exceptions keep their wording; toast placement is
 * checked separately. Utility uses "Screens", "Main screen", "Bottom screen" and "Dim screens after".
 * Overlay descriptions, GAMES ONLY, usage-access text and `Fan N% · GPU T°C` share plain wording;
 * the "Telemetry" title and "Performance overlay" row name remain.
 *
 * Busy copy distinguishes starting from running. Starting keeps its current sentence; running uses
 * "A game or app is still open. Close it first, then try again." Both sentences and short labels are
 * authored once, side by side in feature-library; app's session ledger chooses which refusal applies.
 * Companion payloads carry short labels but no production reader paints them
 * (`SessionMutexRefusalWiringTest`). The running label "Still open" remains proposed copy.
 *
 * Scans read string literals across modules, skipping comments and handling URLs without treating
 * their `//` as a comment. Each scan reports offending literals and proves it read the new wording,
 * so an empty source read cannot pass vacuously. The literal reader has its own fixtures.
 */
class LauncherCopyContractSourcePinTest {

    @Test
    fun `the literal reader returns text and enclosing call, and skips comments, char literals and backticked names`() {
        val source = listOf(
            "// \"Could not move\" in a line comment",
            "/* outer /* nested \"Could not\" */ still a comment \"OPEN APPS\" */",
            "/** KDoc quoting \"Could not move … to the main screen\". */",
            "val quote = '\"'",
            "val escapedQuote = '\\\"'",
            "val url = \"https://example.com/a\"; val after = \"Could not after a url\"",
            "val template = \"Could not move \${app?.label ?: \"the app\"} to the bottom screen\"",
            "val simple = \"Display runs at \$hz Hz.\"",
            "val escaped = \"Couldn\\u2019t start \\\"here\\\"\"",
            "val raw = \"\"\"Raw \"quoted\" OPEN APPS\"\"\"",
            "fun `a \"quoted\" name`() = \"inside a function\"",
            "Log.w(TAG, \"could not reach a log\")",
            "if (ready) android.util.Log.e(Tag, \"qualified log could not\")",
            "Log.i(TAG, \"x \${f(1)} could not\")",
            "Toast.makeText(context, \"App couldn\\u2019t be launched\", Toast.LENGTH_SHORT).show()",
            "check(ok) { \"could not in a check\" }",
        ).joinToString("\n")

        val literals = kotlinStringLiterals(source)

        assertEquals(
            listOf(
                "https://example.com/a",
                "Could not after a url",
                "Could not move {} to the bottom screen",
                "the app",
                "Display runs at {} Hz.",
                "Couldn’t start \"here\"",
                "Raw \"quoted\" OPEN APPS",
                "inside a function",
                "could not reach a log",
                "qualified log could not",
                "x {} could not",
                "App couldn’t be launched",
                "could not in a check",
            ),
            literals.map { it.text },
        )
        assertEquals(6, literals.first { it.text == "Could not after a url" }.line)
        assertEquals(7, literals.first { it.text == "the app" }.line)
        assertEquals(
            listOf("could not reach a log", "qualified log could not", "x {} could not"),
            literals.filter { it.isLogArgument() }.map { it.text },
        )
        assertEquals("Toast.makeText", literals.first { it.text == "App couldn’t be launched" }.call)
        assertEquals("", literals.first { it.text == "could not in a check" }.call)
    }

    @Test
    fun `O4 no string literal a player can see says could not`() {
        val literals = productionLiterals()

        val couldNot = Regex("\\bcould not\\b", RegexOption.IGNORE_CASE)
        val found = literals.flatMap { (path, list) ->
            list.filter { couldNot.containsMatchIn(it.text) && !it.isLogArgument() }.map { path to it }
        }
        val counts = found.groupingBy { (path, literal) -> "$path  ${literal.text}" }.eachCount()
        val beyondNeverShown = counts.filter { (key, count) -> count > (CouldNotNeverShown[key] ?: 0) }.keys
        val hits = found
            .filter { (path, literal) -> "$path  ${literal.text}" in beyondNeverShown }
            .map { (path, literal) -> "$path:${literal.line}  ${literal.text}" }
            .sorted()
        assertEquals(
            "A failure a player can read says “Couldn’t …”, mid-sentence too. These " +
                "production literals still say “could not”, are not a Log argument, and are not in " +
                "CouldNotNeverShown:\n" + hits.joinToString("\n"),
            emptyList<String>(),
            hits,
        )

        assertLiteral(literals, MainActivityPath, "Couldn’t restore the bottom screen")
        assertLiteral(literals, RetroAchievementsModalPath, "Couldn’t check")
        assertLiteral(literals, AppCoordinatorPath, "App couldn’t be launched")
        assertLiteral(literals, AppCoordinatorPath, "Saved, but the current art couldn’t be changed.")
        assertLiteral(literals, NativeShelfCoordinatorPath, "App couldn’t be launched")
        assertLiteral(literals, DatabaseRecoveryGatePath, "MIMIR COULDN’T PROVE EVERY LEGACY FILE LOCATION IN DATABASE ")
        assertLiteral(
            literals,
            RecoveryRestoreServicePath,
            "Recovery restore refused because the mandatory safety archive couldn’t be created.",
        )
    }

    @Test
    fun `O3 the Tabs drawer is titled TABS and no production literal says OPEN APPS`() {
        val literals = productionLiterals()

        val hits = literals.hits { "OPEN APPS" in it }
        assertEquals(
            "The drawer is titled like the TABS coin that opens it. These production " +
                "literals still say OPEN APPS:\n" + hits.joinToString("\n"),
            emptyList<String>(),
            hits,
        )

        assertLiteral(literals, RecentsDrawerPath, "ᛗ · TABS")
    }

    @Test
    fun `O3 every where-it-opens label says Opens on`() {
        val literals = productionLiterals()

        val hits = literals.hits { text -> text == "ᛗ · OPEN APP" || RetiredWhereItOpens.any { it in text } }
        assertEquals(
            "Every where-it-opens label says “Opens on”. These production literals " +
                "still use a retired name:\n" + hits.joinToString("\n"),
            emptyList<String>(),
            hits,
        )

        assertLiteral(literals, PlatformShelfPath, "Opens on")
        // V7 (2026-10-02): the two "Opens on" dialogs are one, so the header and the heading fallback live once, in the choice dialog.
        listOf("ᛗ · OPENS ON", "Choose where it opens").forEach { assertLiteral(literals, ChoiceDialogPath, it) }
        listOf("Hold an app to choose where it opens", "Change where it opens").forEach { assertLiteral(literals, CompanionSurfacePath, it) }
    }

    @Test
    fun `O6 Utility copy says screen, and display survives only in the display-rate family`() {
        val literals = productionLiterals()
        UtilityCopyPaths.forEach { assertTrue("$it was not scanned -- the walk is broken", it in literals) }

        val displayWording = UtilityCopyPaths
            .flatMap { path ->
                literals.getValue(path)
                    .filter { it.text.contains("display", ignoreCase = true) }
                    .map { "$path  ${it.text}" }
            }
            .groupingBy { it }
            .eachCount()
        val beyondKept = displayWording.filter { (literal, count) -> count > (UtilityDisplayKept[literal] ?: 0) }
        assertEquals(
            "Utility names screens, not displays; only UtilityDisplayKept may still " +
                "say display. Literal -> count found:\n" + beyondKept.entries.joinToString("\n"),
            emptyMap<String, Int>(),
            beyondKept,
        )
        // A kept entry that matches nothing is stale, and a stale entry would silently admit a new use of the word.
        assertEquals(
            "UtilityDisplayKept names literals the Utility copy no longer contains; remove them",
            emptySet<String>(),
            UtilityDisplayKept.keys - displayWording.keys,
        )

        listOf(
            "Screens",
            "Main screen",
            "Main screen (Mimir only)",
            "Bottom screen",
            "Bottom screen (Mimir only)",
            "Dim screens after",
            "Allow Android access for main screen changes.",
        ).forEach { assertLiteral(literals, BrightnessOverlayPath, it) }
    }

    @Test
    fun `O6 the performance overlay row and its neighbours use the plain wording`() {
        val literals = productionLiterals()

        val hits = UtilityCopyPaths.flatMap { path ->
            literals.getValue(path)
                .filter { literal -> RetiredOverlayWording.any { it in literal.text } }
                .map { "$path:${it.line}  ${it.text}" }
        }
        assertEquals(
            "These Utility literals still use the " +
                "retired overlay wording:\n" + hits.joinToString("\n"),
            emptyList<String>(),
            hits,
        )

        listOf(
            "Off.",
            "Shows over games you open from Mimir.",
            "Always on, over Mimir and every app.",
            "Lets Mimir Tweaker read system stats for the overlay.",
            "Fan {}%",
            " · GPU {}°C",
            "Telemetry",
            "Performance overlay",
        ).forEach { assertLiteral(literals, BrightnessOverlayPath, it) }
        assertLiteral(literals, PerformanceOverlaySettingsPath, "GAMES ONLY")
    }

    @Test
    fun `O15 the busy copy is written once, starting beside running, and no site writes its own`() {
        val literals = productionLiterals()

        val found = literals.flatMap { (path, list) ->
            list.filter { BusyCopy.containsMatchIn(it.text) }.map { path to it }
        }
        val counts = found.groupingBy { (path, literal) -> "$path  ${literal.text}" }.eachCount()
        assertEquals(
            "A refusal's busy copy is written once, in " +
                "GameLaunchUiState.kt, and a site reaches it only through gameLaunchSessionHeldMessage or " +
                "gameLaunchSessionHeldRailLabel. Production literals found:\n" +
                found.map { (path, literal) -> "$path:${literal.line}  ${literal.text}" }.sorted().joinToString("\n"),
            BusyCopyWrittenOnce,
            counts,
        )

        listOf(
            "Mimir is already launching something else. Wait for it to finish, then try again.",
            "A game or app is still open. Close it first, then try again.",
            "Launch busy",
            "Still open",
        ).forEach { assertLiteral(literals, GameLaunchUiStatePath, it) }
    }

    private fun productionLiterals(): Map<String, List<SourceLiteral>> {
        val literals = ProductionLiterals.value
        assertTrue(
            "scanned only ${literals.size} production .kt files -- the walk is broken, not the tree clean",
            literals.size > 300,
        )
        return literals
    }

    private fun assertLiteral(literals: Map<String, List<SourceLiteral>>, path: String, text: String) {
        val file = literals[path]
        assertTrue("$path was not scanned -- the walk is broken, not the tree clean", file != null)
        assertTrue(
            "$path holds no literal \"$text\" -- either the reader is blind or the wording moved",
            file!!.any { it.text == text },
        )
    }

    private fun Map<String, List<SourceLiteral>>.hits(matches: (String) -> Boolean): List<String> =
        flatMap { (path, list) -> list.filter { matches(it.text) }.map { "$path:${it.line}  ${it.text}" } }.sorted()

    private companion object {
        const val AppSource = "app/src/main/kotlin/com/gruning/mimir/rewrite"
        const val TweakerClientSource = "core-tweaker-client/src/main/kotlin/com/gruning/mimir/rewrite/core/tweaker"
        const val MainActivityPath = "app/src/main/kotlin/com/gruning/mimir/rewrite/MainActivity.kt"
        const val PresentationPath = "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt"
        const val BrightnessOverlayPath = "app/src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"
        const val AppBrightnessCoordinatorPath =
            "app/src/main/kotlin/com/gruning/mimir/rewrite/AppBrightnessCoordinator.kt"
        const val TweakerRoutePolicyPath = "app/src/main/kotlin/com/gruning/mimir/rewrite/TweakerRoutePolicy.kt"
        const val PerformanceOverlaySettingsPath =
            "app/src/main/kotlin/com/gruning/mimir/rewrite/PerformanceOverlaySettings.kt"
        const val AppCoordinatorPath = "$AppSource/MimirAppCoordinator.kt"
        const val NativeShelfCoordinatorPath = "$AppSource/MimirNativeShelfCoordinator.kt"
        const val DatabaseRecoveryGatePath = "$AppSource/DatabaseRecoveryGate.kt"
        const val RecoveryRestoreServicePath = "$AppSource/RecoveryRestoreService.kt"
        const val RecentsDrawerPath =
            "feature-companion/src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionRecentsDrawer.kt"
        const val ChoiceDialogPath =
            "feature-companion/src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionChoiceDialog.kt"
        const val CompanionSurfacePath =
            "feature-companion/src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt"
        const val PlatformShelfPath =
            "feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/CompanionPlatformShelf.kt"
        const val RetroAchievementsModalPath =
            "feature-settings/src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/RetroAchievementsKeyEditModal.kt"

        /**
         * O4: the "could not" literals left as they are because no player reads them, each with its count. A `Log`
         * argument needs no entry. This list is not checked for stale entries: most of them live in Tweaker files other
         * lanes edit, and an entry that no longer matches admits only that exact sentence in that exact file.
         */
        val CouldNotNeverShown: Map<String, Int> = mapOf(
            // Written to remap_journal.lastError for diagnosis and copied into recovery archives; nothing renders it.
            "$AppSource/GameIdentityRemapReplayer.kt  fully acknowledged row could not be consumed" to 1,
            // Onboarding failure reasons only choose which fixed sentence the FAILED step paints (OnboardingFlow.kt);
            // the reason itself is never painted.
            "$AppSource/MimirOnboardingCoordinator.kt  " +
                "Mimir could not retain access to that folder. Choose it again and allow file access." to 1,
            "$AppSource/MimirOnboardingCoordinator.kt  Mimir could not save this setup step. Choose the folder again." to 1,
            "$AppSource/OnboardingScanWorker.kt  Mimir could not finish reading that folder." to 1,
            // Tweaker operation reasons reach the diagnostic launch log only: a skipped preparation is silent
            // (allowsLaunchWarningFor), an uncertain profile paints PROFILE STATUS UNCONFIRMED, and a failed launch
            // paints a fixed line from warningOrNull.
            "$AppSource/TweakerBridge.kt  Prior profile could not be re-admitted after cancellation" to 1,
            "$AppSource/TweakerFpsLaunchPort.kt  Frame-limit request could not be created" to 1,
            "$TweakerClientSource/TweakerPreparation.kt  Tweaker restore failed — stock settings could not be confirmed" to 2,
            "$TweakerClientSource/TweakerPreparation.kt  " +
                "Performance profile apply did not take — stock restore could not be confirmed" to 1,
            "$TweakerClientSource/TweakerPreparation.kt  Tweaker apply failed — stock restore could not be confirmed" to 2,
            "$TweakerClientSource/TweakerSession.kt  V4 query deadline could not be created" to 1,
            "$TweakerClientSource/TweakerV4Owner.kt  Apply request could not be created" to 1,
        )

        /** O3: what "where it opens" was called before the copy update. The app chooser's `ᛗ · OPEN APP` is matched whole. */
        val RetiredWhereItOpens = listOf(
            "Display option", // Bottom launch card row
            "DISPLAY FOR THIS GAME", // game chooser header, editing
            "CHOOSE DISPLAY", // game chooser header, first use
            "DEFAULT LAUNCH SCREEN", // app chooser header, editing
            "DEFAULT SCREEN", // app picker hint
            "default screen", // pin action
            "launch screen", // app chooser title when the app has no label
        )

        /** O6: the files that author Utility's copy, on either screen. */
        val UtilityCopyPaths = listOf(
            BrightnessOverlayPath,
            AppBrightnessCoordinatorPath,
            "app/src/main/kotlin/com/gruning/mimir/rewrite/AppBrightnessHost.kt",
            "app/src/main/kotlin/com/gruning/mimir/rewrite/BrightnessController.kt",
            TweakerRoutePolicyPath,
            "app/src/main/kotlin/com/gruning/mimir/rewrite/FilesRoutePolicy.kt",
            PerformanceOverlaySettingsPath,
            "app/src/main/kotlin/com/gruning/mimir/rewrite/MButtonRuntime.kt",
        )

        /**
         * O6: the only Utility literals that may still say "display", each with the number of times it appears.
         * The panel row that used to justify most of this family was removed outright (round 6, R6-5E-07),
         * taking its section label with it. What remains is logcat only, never painted: `display=` is the
         * Bottom ᛗ's display id.
         */
        val UtilityDisplayKept: Map<String, Int> = mapOf(
            // O9's idle lines, logcat only and never painted: `display=` is the Bottom ᛗ's display id, and
            // MButtonBottomForeignAppCoverTest reads both lines as written.
            "app/src/main/kotlin/com/gruning/mimir/rewrite/MButtonRuntime.kt  idle cover={} role={} display={} policy={} " to 1,
            "app/src/main/kotlin/com/gruning/mimir/rewrite/MButtonRuntime.kt  idle deadline role={} display={} " to 1,
            // The target line reconcile logs on each role change, logcat only:
            // `display=` is the presentation display id the button was placed for.
            "app/src/main/kotlin/com/gruning/mimir/rewrite/MButtonRuntime.kt  target {} -> {} display={} " to 1,
        )

        /** O6, second half: the overlay retired wording, matched inside Utility's copy files. */
        val RetiredOverlayWording = listOf(
            "ONLY GAMES",
            "Automatic fan",
            "No performance telemetry is shown",
            "launched from Mimir is active",
            "Visible across Mimir and your active apps",
            "provide optional system telemetry",
        )

        const val GameLaunchUiStatePath =
            "feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/GameLaunchUiState.kt"
        const val HomeShelfCoordinatorPath = "$AppSource/HomeShelfCoordinator.kt"

        /**
         * O15: the words a mutex refusal's busy copy is made of, in its sentence and in its short status label. The word
         * boundary keeps out the refusals that say "still opening" ("Still opening the previous app", "An app is still
         * opening or moving. …"): those are other refusals with wording of their own, and not this busy-copy contract's.
         */
        val BusyCopy = Regex("\\b(already launching|still open|close it first|launch busy)\\b", RegexOption.IGNORE_CASE)

        /**
         * O15: the only production literals [BusyCopy] may match, each written once and side by side: today's starting
         * sentence, the ruled running sentence, and their short status labels, which are not painted today ("Still open"
         * remains proposed copy).
         *
         * R6-P0-02 follow-up (policy, "say so" copy): a DIFFERENT refusal -- Main's PLAY on another game while one
         * is parked, when release-at-the-press could not clear it -- gets its own sentence in
         * [HomeShelfCoordinatorPath] (`parkedGameStillPausedMessage`), coincidentally sharing [BusyCopy]'s "close it
         * first" wording. Not a duplicate of O15's busy copy: different condition (parked, not merely running),
         * different sentence, one call site (`emitHomeShelfGameRefusalMessage`) shared by the Home row and the Home
         * launch card -- so one template literal (interpolated title) and one plain literal (no title resolved), each
         * written once.
         */
        val BusyCopyWrittenOnce: Map<String, Int> = mapOf(
            "$GameLaunchUiStatePath  Mimir is already launching something else. Wait for it to finish, then try again." to 1,
            "$GameLaunchUiStatePath  A game or app is still open. Close it first, then try again." to 1,
            "$GameLaunchUiStatePath  Launch busy" to 1,
            "$GameLaunchUiStatePath  Still open" to 1,
            "$HomeShelfCoordinatorPath  {} is still paused. Return to it from the lower screen and close it first." to 1,
            "$HomeShelfCoordinatorPath  A game is still paused. Return to it from the lower screen and close it first." to 1,
        )

        val ProductionLiterals = lazy { scanProductionLiterals() }
    }
}

/**
 * One string literal: the text Kotlin builds from it (each template expression written as `{}`), and the innermost call
 * whose parentheses enclose it, as written before its `(` (`Log.w`, `Toast.makeText`), or "" outside any call.
 */
private data class SourceLiteral(val line: Int, val text: String, val call: String = "")

private val LogCall = Regex("""(^|\.)Log\.(v|d|i|w|e|wtf)$""")

/** A literal passed directly to `android.util.Log`: it goes to logcat and is never painted. */
private fun SourceLiteral.isLogArgument(): Boolean = LogCall.containsMatchIn(call)

/**
 * Every root-build module's production Kotlin (`<module>/src/main/kotlin`), keyed by repo-relative path. The helper app
 * under `mimir-tweaker/` is its own Gradle build with its own gates and is not read.
 */
private fun scanProductionLiterals(): Map<String, List<SourceLiteral>> {
    val root = listOf(File(".."), File("."), File("../.."))
        .map(File::getCanonicalFile)
        .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "app/src/main").isDirectory }
        ?: error("repo root not found from ${File(".").absolutePath}")
    return root.listFiles { file: File -> file.isDirectory }.orEmpty()
        .map { File(it, "src/main/kotlin") }
        .filter(File::isDirectory)
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        .associate { file -> file.relativeTo(root).invariantSeparatorsPath to kotlinStringLiterals(file.readText()) }
}

/**
 * The string literals of Kotlin [source], in source order. Line comments, block comments (nested too), character
 * literals and backticked names are skipped; escapes are decoded; a `$name` or `${…}` template is written as `{}`, and
 * a string inside a `${…}` is returned as a literal of its own.
 */
private fun kotlinStringLiterals(source: String): List<SourceLiteral> = KotlinLiteralReader(source).read()

private class KotlinLiteralReader(private val s: String) {
    private var i = 0
    private val starts = mutableListOf<Int>()
    private val texts = mutableListOf<String>()
    private val calls = mutableListOf<String>()
    private val openCalls = ArrayDeque<String>()
    private val newlines = s.indices.filter { s[it] == '\n' }.toIntArray()

    fun read(): List<SourceLiteral> {
        code(insideTemplate = false)
        return starts.indices.sortedBy { starts[it] }.map { SourceLiteral(lineOf(starts[it]), texts[it], calls[it]) }
    }

    private fun lineOf(offset: Int): Int {
        val index = Arrays.binarySearch(newlines, offset)
        return 1 + if (index >= 0) index else -(index + 1)
    }

    private fun at(offset: Int): Char? = s.getOrNull(i + offset)

    /** Code to the end of the source or, [insideTemplate], through the `}` that closes the template. */
    private fun code(insideTemplate: Boolean) {
        var depth = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '/' && at(1) == '/' -> while (i < s.length && s[i] != '\n') i++
                c == '/' && at(1) == '*' -> blockComment()
                c == '"' && at(1) == '"' && at(2) == '"' -> string(raw = true)
                c == '"' -> string(raw = false)
                c == '\'' -> charLiteral()
                c == '`' -> {
                    i++
                    while (i < s.length && s[i] != '`' && s[i] != '\n') i++
                    i++
                }
                insideTemplate && c == '{' -> {
                    depth++
                    i++
                }
                insideTemplate && c == '}' -> {
                    i++
                    if (depth == 0) return
                    depth--
                }
                c == '(' -> {
                    openCalls.addLast(callNameBefore(i))
                    i++
                }
                c == ')' -> {
                    openCalls.removeLastOrNull()
                    i++
                }
                else -> i++
            }
        }
    }

    /** The dotted name written just before the `(` at [paren], such as `android.util.Log.w`; "" when there is none. */
    private fun callNameBefore(paren: Int): String {
        var end = paren
        while (end > 0 && s[end - 1].isWhitespace()) end--
        var start = end
        while (start > 0 && (s[start - 1].isLetterOrDigit() || s[start - 1] == '_' || s[start - 1] == '.')) start--
        return s.substring(start, end)
    }

    private fun blockComment() {
        var depth = 0
        while (i < s.length) {
            when {
                s.startsWith("/*", i) -> {
                    depth++
                    i += 2
                }
                s.startsWith("*/", i) -> {
                    depth--
                    i += 2
                    if (depth == 0) return
                }
                else -> i++
            }
        }
    }

    private fun charLiteral() {
        val end = when {
            at(1) == '\\' && at(2) == 'u' -> i + 7
            at(1) == '\\' -> i + 3
            else -> i + 2
        }
        i = if (s.getOrNull(end) == '\'') end + 1 else i + 1
    }

    private fun string(raw: Boolean) {
        val start = i
        val call = openCalls.lastOrNull().orEmpty()
        i += if (raw) 3 else 1
        val text = StringBuilder()
        while (i < s.length) {
            val c = s[i]
            when {
                raw && s.startsWith("\"\"\"", i) -> {
                    var quotes = 0
                    while (s.getOrNull(i) == '"') {
                        quotes++
                        i++
                    }
                    repeat(quotes - 3) { text.append('"') }
                    break
                }
                !raw && c == '"' -> {
                    i++
                    break
                }
                !raw && c == '\n' -> break
                !raw && c == '\\' -> escape(text)
                c == '$' && at(1) == '{' -> {
                    text.append("{}")
                    i += 2
                    code(insideTemplate = true)
                }
                c == '$' && at(1)?.let { it.isLetter() || it == '_' } == true -> {
                    text.append("{}")
                    i++
                    while (s.getOrNull(i)?.let { it.isLetterOrDigit() || it == '_' } == true) i++
                }
                else -> {
                    text.append(c)
                    i++
                }
            }
        }
        starts += start
        texts += text.toString()
        calls += call
    }

    private fun escape(text: StringBuilder) {
        when (val escaped = at(1)) {
            't' -> text.append('\t')
            'b' -> text.append('\b')
            'n' -> text.append('\n')
            'r' -> text.append('\r')
            'u' -> {
                val code = s.substring(i + 2, minOf(i + 6, s.length)).toIntOrNull(16)
                if (code != null) {
                    text.append(code.toChar())
                    i += 6
                    return
                }
                text.append('u')
            }
            null -> Unit
            else -> text.append(escaped)
        }
        i += 2
    }
}
