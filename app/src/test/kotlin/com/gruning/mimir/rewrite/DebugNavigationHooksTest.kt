package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailAction
import com.gruning.mimir.rewrite.feature.files.FileBrowserMode
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation-hooks lane's differential coverage. Two things this test class deliberately does NOT
 * do, stated up front rather than left implied:
 *
 * - It never instantiates [MainActivity] (a [android.app.Activity]) or composes [MimirAppCoordinator] (a
 *   2,000+ line composable requiring the whole app graph). Neither is reachable from a JVM unit test —
 *   `CompanionPrimaryResumePolicyTest`'s "onResume routes its Tweaker settlement through the gate" test
 *   already established the precedent this class follows: read the call site as source instead
 *   (`mimir.mainActivitySource` / `mimir.mimirAppCoordinatorSource`, wired in `app/build.gradle.kts`).
 * - It never proves the four real production entry points
 *   ([com.gruning.mimir.rewrite.MimirNavigationCoordinator.openPlatformInLibrary],
 *   `MimirSettingsCoordinator.onRowActivate`, `MimirOverlayCoordinator.dispatch`,
 *   `com.gruning.mimir.rewrite.FileBrowserStore.open`) still behave the way this lane's doc comments
 *   claim — that is existing, pre-dating behavior this lane reuses rather than changes, and stayed
 *   provably untouched (see the source-characterization tests below, which fail loudly if a future edit
 *   routes the bridge around them).
 *
 * What IS pinned here: [DebugNavigationBridge]'s publish/acknowledge state machine (pure, no Android
 * dependency), the three `mimir.*` extra parsers (pure), and — via source characterization — that
 * `MainActivity.runDebugIntentHooks()` still calls all four new hooks INSIDE the `debugIntentHookGate.run`
 * block, that each hook still returns early on an unparseable extra rather than falling back to a
 * default, and that `MimirAppCoordinator`'s consumer still dispatches through the four real functions
 * named above and still acknowledges every request it handles — an unacknowledged request would replay
 * itself on every future recomposition, a regression this test would catch if a future edit dropped the
 * `DebugNavigationBridge.acknowledge(request.id)` call.
 */
class DebugNavigationHooksTest {

    @After
    fun resetBridge() {
        // The bridge is a process-global singleton (same shape as MButtonNavigationCommands); leaving a
        // stray pending request would leak into whichever test runs next in this JVM.
        DebugNavigationBridge.pending.value?.let { DebugNavigationBridge.acknowledge(it.id) }
    }

    // --- parseOpenGamelistPlatformId -------------------------------------------------------------

    @Test
    fun `openGamelist parse is inert when the extra is absent`() {
        assertNull(parseOpenGamelistPlatformId(null))
    }

    @Test
    fun `openGamelist parse is inert when the extra is blank`() {
        assertNull(parseOpenGamelistPlatformId(""))
        assertNull(parseOpenGamelistPlatformId("   "))
    }

    @Test
    fun `openGamelist parse trims and wraps a real value`() {
        assertEquals(PlatformId("arcade"), parseOpenGamelistPlatformId("  arcade  "))
    }

    // --- parseOpenOverlayRowId --------------------------------------------------------------------

    @Test
    fun `openOverlay parse is inert when the extra is absent or blank`() {
        assertNull(parseOpenOverlayRowId(null))
        assertNull(parseOpenOverlayRowId(""))
        assertNull(parseOpenOverlayRowId("  "))
    }

    @Test
    fun `openOverlay parse trims a real row id and does not canonicalize`() {
        // Canonicalization is MimirSettingsCoordinator.onRowActivate's own job (canonicalSettingsDetailRowId,
        // feature-settings/SettingsFocus.kt) — this parser only strips whitespace, it does not resolve aliases.
        assertEquals("profile/theme", parseOpenOverlayRowId("  profile/theme  "))
    }

    // --- parseOpenFilesMode ---------------------------------------------------------------------

    @Test
    fun `openFiles parse is inert when the extra is absent or blank`() {
        assertNull(parseOpenFilesMode(null))
        assertNull(parseOpenFilesMode(""))
        assertNull(parseOpenFilesMode("   "))
    }

    @Test
    fun `openFiles parse is inert on an unrecognised value, never a default`() {
        // Unlike parseOpenGamelistPlatformId / parseOpenOverlayRowId (which wrap whatever non-blank
        // string survives trimming), an unparseable mode must read as "hook not fired," never silently
        // become MANAGE -- the destructive half: delete, move, the queue. Casing is exact-match, not
        // normalized, so a wrong-case value is exactly this hazard, not an edge case.
        assertNull(parseOpenFilesMode("PICK"))
        assertEquals(FileBrowserMode.PICK_IMAGE, parseOpenFilesMode("pickimage"))
        assertNull(parseOpenFilesMode("MANAGE"))
        assertNull(parseOpenFilesMode("delete_everything"))
    }

    @Test
    fun `openFiles parse trims and maps pick and manage to their FileBrowserMode`() {
        assertEquals(FileBrowserMode.PICK, parseOpenFilesMode("pick"))
        assertEquals(FileBrowserMode.PICK, parseOpenFilesMode("  pick  "))
        assertEquals(FileBrowserMode.MANAGE, parseOpenFilesMode("manage"))
        assertEquals(FileBrowserMode.MANAGE, parseOpenFilesMode("  manage  "))
    }

    // --- DebugNavigationBridge ----------------------------------------------------------------------

    @Test
    fun `bridge starts empty`() {
        assertNull(DebugNavigationBridge.pending.value)
    }

    @Test
    fun `publish makes the request visible on pending`() {
        val request = DebugNavigationRequest.OpenGamelist(PlatformId("arcade"))

        DebugNavigationBridge.publish(request)

        assertEquals(request, DebugNavigationBridge.pending.value)
    }

    @Test
    fun `acknowledge with the wrong id leaves the request pending`() {
        val request = DebugNavigationRequest.DismissTopOverlay()
        DebugNavigationBridge.publish(request)

        DebugNavigationBridge.acknowledge(UUID.randomUUID())

        assertEquals(request, DebugNavigationBridge.pending.value)
    }

    @Test
    fun `acknowledge with the exact id clears the request`() {
        val request = DebugNavigationRequest.OpenSettingsRow("profile/name")
        DebugNavigationBridge.publish(request)

        DebugNavigationBridge.acknowledge(request.id)

        assertNull(DebugNavigationBridge.pending.value)
    }

    @Test
    fun `each default-constructed request gets a distinct id`() {
        val first = DebugNavigationRequest.DismissTopOverlay()
        val second = DebugNavigationRequest.DismissTopOverlay()

        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `a later publish replaces an unacknowledged earlier one`() {
        // Mirrors MButtonNavigationCommands' single-pending-slot shape: this bridge does not queue, the
        // newest request simply overwrites whatever nobody consumed yet.
        val first = DebugNavigationRequest.OpenGamelist(PlatformId("arcade"))
        val second = DebugNavigationRequest.OpenGamelist(PlatformId("snes"))
        DebugNavigationBridge.publish(first)

        DebugNavigationBridge.publish(second)

        assertEquals(second, DebugNavigationBridge.pending.value)
        assertFalse(DebugNavigationBridge.pending.value === first)
    }

    @Test
    fun `OpenFiles requests carry the parsed mode through publish`() {
        val request = DebugNavigationRequest.OpenFiles(FileBrowserMode.MANAGE)

        DebugNavigationBridge.publish(request)

        val pending = DebugNavigationBridge.pending.value as? DebugNavigationRequest.OpenFiles
        assertEquals(FileBrowserMode.MANAGE, pending?.mode)
    }

    // --- Source characterization: MainActivity wires all four hooks behind the debug gate ----------

    private val mainActivitySource: String by lazy {
        File(requireNotNull(System.getProperty("mimir.mainActivitySource")) {
            "mimir.mainActivitySource system property missing — check app/build.gradle.kts testOptions"
        }).readText()
    }

    @Test
    fun `runDebugIntentHooks calls all four new nav hooks inside the debug gate`() {
        val gateBlock = Regex(
            """runDebugIntentHooks\(\) = debugIntentHookGate\.run \{(.*?)\n {4}\}""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(mainActivitySource)
        assertTrue("could not locate runDebugIntentHooks()'s gated block in MainActivity.kt", gateBlock != null)
        val body = gateBlock!!.groupValues[1]

        assertTrue("maybeOpenGamelist() must run inside the debug gate", body.contains("maybeOpenGamelist()"))
        assertTrue("maybeOpenFiles() must run inside the debug gate", body.contains("maybeOpenFiles()"))
        assertTrue("maybeOpenOverlay() must run inside the debug gate", body.contains("maybeOpenOverlay()"))
        assertTrue(
            "maybeLaunchFirstReadyGame() must run inside the debug gate",
            body.contains("maybeLaunchFirstReadyGame()"),
        )
        assertTrue(
            "maybeDismissTopOverlay() must run inside the debug gate",
            body.contains("maybeDismissTopOverlay()"),
        )
    }

    @Test
    fun `the gated hook functions publish on the bridge and nothing else does`() {
        // Every call to DebugNavigationBridge.publish in the whole file must originate from one of the
        // gated hook functions — a stray call site anywhere else would be an ungated producer.
        //
        // FIVE since E1 (2026-09-01): `maybeLaunchFirstReadyGame` joined them. It used to call
        // `LaunchInjection.controller(...).launch(...)` directly, which skips
        // `LibraryGameSessionEvent.Preparing` and therefore made the probe unusable as evidence about
        // companion attribution. It now goes through the bridge to `LibraryViewModel.launch`.
        //
        // SIX since 2026-09-03: `maybeShelfQuickAction` joined them, for `--es mimir.shelfAction
        // <home|library|utility|settings|apps|files>`. The regression report described the Thor slow traversing
        // platforms on the Library shelf and no adb input could reach that surface — DPAD_DOWN will not
        // leave the shelf for the rail, BUTTON_R1 will not change destination (both verified by
        // screenshot), and `maybeOpenGamelist` lands one level below it inside a platform's gamelist.
        //
        // **Raising this number is meant to be deliberate.** Every producer must sit inside
        // `runDebugIntentHooks()`, which is `debugIntentHookGate.run { ... }` and compiled to nothing in
        // release; the count is what forces a new call site to be justified rather than absorbed.
        val publishSites = Regex("""DebugNavigationBridge\.publish\(""").findAll(mainActivitySource).count()
        assertEquals("unexpected DebugNavigationBridge.publish call-site count in MainActivity.kt", 6, publishSites)
    }

    @Test
    fun `the launch probe dispatches through the session seam, never the raw controller`() {
        // **This is the pin that keeps the probe honest.** `LaunchInjection.controller(...).launch(...)`
        // fires the correct Intent at the correct package and emits NO session event, so a probe that
        // uses it can prove dispatch and can prove nothing about what screen 2 will say. E1's companion
        // symptom was first reported from a run of exactly that.
        val body = Regex(
            """private fun maybeLaunchFirstReadyGame\(\) \{(.*?)\n {4}\}""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(mainActivitySource)?.groupValues?.get(1)
        assertTrue("could not locate maybeLaunchFirstReadyGame() in MainActivity.kt", body != null)
        // **Comments are stripped before matching, and that is not tidiness.** The fix's own KDoc
        // explains what this function used to call, so it NAMES the banned literal — a grep pin that
        // reads raw source fails on the sentence describing the fix. Same defect class as the gallery
        // caption pin (`f3e620fb`), which passed while the defect was present for the mirror-image
        // reason. A pin must read what the compiler reads.
        val source = body!!.lines().joinToString("\n") { it.substringBefore("//") }
        assertTrue(
            "maybeLaunchFirstReadyGame must publish DebugNavigationRequest.LaunchGame",
            source.contains("DebugNavigationRequest.LaunchGame("),
        )
        assertFalse(
            "maybeLaunchFirstReadyGame must not dispatch through LaunchInjection.controller",
            source.contains("LaunchInjection.controller"),
        )
    }

    @Test
    fun `each new hook reads its own extra and returns early when absent`() {
        assertTrue(
            mainActivitySource.contains(
                """parseOpenGamelistPlatformId(intent?.getStringExtra("mimir.openGamelist")) ?: return""",
            ),
        )
        assertTrue(
            mainActivitySource.contains(
                """parseOpenFilesMode(intent?.getStringExtra("mimir.openFiles")) ?: return""",
            ),
        )
        assertTrue(
            mainActivitySource.contains(
                """parseOpenOverlayRowId(intent?.getStringExtra("mimir.openOverlay")) ?: return""",
            ),
        )
        assertTrue(
            mainActivitySource.contains(
                """intent?.getBooleanExtra("mimir.dismissTopOverlay", false) != true) return""",
            ),
        )
    }

    @Test
    fun `maybeOpenFiles publishes OpenFiles built from a variable named mode`() {
        // Differential-tested (revert -> capture -> restore): hardcoding `val mode = FileBrowserMode.MANAGE`
        // in maybeOpenFiles does NOT fail this test — it still publishes `OpenFiles(mode)` with that exact
        // text, so this test alone does not prove `mode` came from the parser. That half is what "each new
        // hook reads its own extra and returns early when absent" pins (`val mode =
        // parseOpenFilesMode(...) ?: return`) — a differential revert of THAT line is what actually catches
        // the hardcoded-mode defect. This test's own job is narrower: catch a publish call that got
        // dropped, commented out, or rebuilt with a different/renamed/literal argument — which the
        // count-based test above cannot localize to openFiles specifically (it only proves a fourth publish
        // site exists somewhere in the file, not that this one still names `mode`).
        assertTrue(
            "maybeOpenFiles must publish DebugNavigationRequest.OpenFiles(mode)",
            mainActivitySource.contains("DebugNavigationBridge.publish(DebugNavigationRequest.OpenFiles(mode))"),
        )
    }

    // --- Source characterization: MimirAppCoordinator dispatches through the real production paths ---

    private val coordinatorSource: String by lazy {
        File(requireNotNull(System.getProperty("mimir.mimirAppCoordinatorSource")) {
            "mimir.mimirAppCoordinatorSource system property missing — check app/build.gradle.kts testOptions"
        }).readText()
    }

    /**
     * The `LaunchedEffect(debugNavigationRequest?.id) { ... }` consumer's body, bounded by the next
     * `\n    }` at the SAME 4-space indent as the `LaunchedEffect(` line itself — not by a bare brace
     * pattern, which would stop early at the nested `when` block's own close. Shared by the tests below so
     * each assertion is scoped to the real consumer, not to whatever else this 2,800+ line file happens to
     * contain (a `.contains` against the whole file would still pass if the pinned call moved to some
     * unrelated function).
     */
    private fun debugNavigationConsumerBlock(): String {
        val match = Regex(
            """LaunchedEffect\(debugNavigationRequest\?\.id\) \{(.*?)\n {4}\}""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(coordinatorSource)
        assertTrue("could not locate the DebugNavigationBridge consumer LaunchedEffect", match != null)
        return match!!.groupValues[1]
    }

    @Test
    fun `the coordinator consumer dispatches every request kind through a real production function`() {
        assertTrue(
            "OpenGamelist must dispatch through the real openPlatformInLibrary, not a synthetic path",
            coordinatorSource.contains("navigation.openPlatformInLibrary(request.platformId)"),
        )
        assertTrue(
            "OpenSettingsRow must dispatch through the real onRowActivate, not a synthetic path",
            coordinatorSource.contains("settings.onRowActivate(request.rowId)"),
        )
        assertTrue(
            "DismissTopOverlay must dispatch through the real overlay CANCEL path, not a synthetic path",
            coordinatorSource.contains("overlays.dispatch(ControllerAction.CANCEL)"),
        )
        val consumerBlock = debugNavigationConsumerBlock()
        assertTrue(
            "OpenFiles must dispatch through the real FileBrowserStore.open, not a synthetic path",
            consumerBlock.contains("FileBrowserStore.open("),
        )
        assertTrue(
            "OpenFiles must forward the REQUEST's parsed mode into FileBrowserStore.open, not a " +
                "hardcoded/default one — this is the wiring half of the pin; parseOpenFilesMode's own " +
                "unit tests are the parser half",
            consumerBlock.contains("mode = request.mode"),
        )
    }

    @Test
    fun `shelfAction parses every rail seat by its on-screen label`() {
        assertEquals(MimirQuickRailAction.HOME, parseShelfQuickAction("home"))
        assertEquals(MimirQuickRailAction.LIBRARY, parseShelfQuickAction("library"))
        assertEquals(MimirQuickRailAction.SETTINGS, parseShelfQuickAction("settings"))
        assertEquals(MimirQuickRailAction.FILES, parseShelfQuickAction("files"))
        // The two seats whose LABEL differs from the enum constant. The label is what the dock reads on
        // screen, so it is what a tester types; the constant name is accepted too rather than being a
        // trap for whoever read the source instead of the device.
        assertEquals(MimirQuickRailAction.BRIGHTNESS, parseShelfQuickAction("utility"))
        assertEquals(MimirQuickRailAction.BRIGHTNESS, parseShelfQuickAction("brightness"))
        assertEquals(MimirQuickRailAction.QUICK_LAUNCH, parseShelfQuickAction("apps"))
        assertEquals(MimirQuickRailAction.QUICK_LAUNCH, parseShelfQuickAction("quick_launch"))
        assertEquals("case and surrounding space must not matter", MimirQuickRailAction.LIBRARY, parseShelfQuickAction("  LiBrArY \n"))
    }

    @Test
    fun `shelfAction fails CLOSED on anything it cannot read`() {
        // Fail-closed for the same reason parseOpenFilesMode is: two of the six seats are not navigation
        // at all -- BRIGHTNESS opens the Utility overlay and QUICK_LAUNCH opens the Apps picker. A typo
        // falling through to a default would land the probe somewhere other than where it says it landed,
        // and a probe that misreports its own surface produces evidence about the wrong one. That is not
        // hypothetical here: this hook was written because three surface substitutions had already
        // happened in the investigation it was built to unblock.
        assertNull(parseShelfQuickAction(null))
        assertNull(parseShelfQuickAction(""))
        assertNull(parseShelfQuickAction("   "))
        assertNull(parseShelfQuickAction("librari"))
        assertNull(parseShelfQuickAction("LIBRARY_SHELF"))
        assertNull(parseShelfQuickAction("shelf"))
    }

    @Test
    fun `shelfAction dispatches through the REAL rail dispatcher, not a destination write`() {
        // The distinction this pin exists for: publishing a MimirDestination would have reached LIBRARY
        // while inventing a second navigation model for the four seats that are not destination writes.
        // onShelfQuickAction is the one function every real rail press already goes through.
        val consumerBlock = debugNavigationConsumerBlock()
        assertTrue(
            "ShelfQuickAction must dispatch through navigation.onShelfQuickAction, not a synthetic path",
            consumerBlock.contains("navigation.onShelfQuickAction(request.action)"),
        )
        assertFalse(
            "the consumer must not write `destination` itself for this request -- that would bypass the " +
                "rail dispatcher the hook exists to replay",
            consumerBlock.contains("navigation.destination = "),
        )
    }

    @Test
    fun `the coordinator consumer acknowledges every request it handles`() {
        // An unacknowledged request would re-fire on every future recomposition (a real regression caught
        // during this lane's own development, before the acknowledge call existed).
        assertTrue(
            "the consumer must acknowledge the request it just dispatched",
            debugNavigationConsumerBlock().contains("DebugNavigationBridge.acknowledge(request.id)"),
        )
    }
}
