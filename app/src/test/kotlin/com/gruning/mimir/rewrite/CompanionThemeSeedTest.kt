package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.view.Display
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.toArgb
import com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.MimirAccentStop
import com.gruning.mimir.rewrite.core.ui.MimirAccentTemp
import com.gruning.mimir.rewrite.core.ui.MimirAppearanceOverrides
import com.gruning.mimir.rewrite.core.ui.MimirRingStop
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.shift
import com.gruning.mimir.rewrite.core.ui.withAppearanceOverrides
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers

/**
 * Round 5c: in a fresh process the Bottom wore Default until Main's composition pushed its first payload.
 *
 * `CompanionState` starts from `CompanionPayload.idle()`, whose theme key is `""`, and `ThemeStore.load` ran only in
 * `MainActivity.onCreate`. A fresh process follows a death, an install or a boot, and the platform restarts the display-4
 * floor before any MainActivity exists. Everything that read the payload's theme before Main's first push copied that
 * empty key and resolved Default: the floor's cover, `holdForColdStart` and `holdForReclaim` (the covers the
 * Presentation shows while they hold), and the restore guard's one read in `CompanionReturnActivity.onCreate`. That
 * guard read copied the payload even after `ThemeStore.load` had run, so Main-first starts were affected too.
 *
 * The theme's file also holds the appearance overrides and the scene style, and only MainActivity loaded those. The
 * Bottom's covers paint the accent tokens the overrides retint, and its stage draws the scene, so a player who changed
 * either saw the authored look there until Main ran.
 *
 * Each test starts from what such a process holds before any component runs: an unthemed idle payload, and appearance
 * holders nothing has loaded over a file that holds the player's choices. The restore guard is the real Activity,
 * reached through a real runtime reclaim, and its ground is read back from its window. Pixels on the Thor's Bottom are
 * device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompanionThemeSeedTest {

    private lateinit var app: Application
    private lateinit var holdScope: CoroutineScope

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        holdScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        CompanionState.holdScopeOverride = holdScope
        CompanionState.holdTimeoutMs = 60_000L
        resetAppearanceToUnloaded()
        CompanionState.set(CompanionPayload.idle())
    }

    @After
    fun tearDown() {
        holdScope.cancel()
        CompanionState.holdScopeOverride = null
        CompanionState.holdTimeoutMs = 3_000L
        resetAppearanceToUnloaded()
        CompanionState.set(CompanionPayload.idle())
        CompanionReturnCoordinator.resetTestOverrides()
        // The appearance loads write Compose snapshot state (MimirAppearanceOverrides) outside any composition, and
        // Compose applies such writes from work it posts to the main looper, which this non-Compose test never idles.
        // Left pending into the end-of-test reset, that broke every later Compose test in this Robolectric sandbox:
        // ArtworkConnectUsernameToPasswordFocusTest, then the overrides test, then ComposeHarnessSmokeTest failed
        // "Compose did not get idle" without these two lines, and stayed green with the restore guard test in the
        // overrides test's place. Apply the writes, then run what they posted.
        Snapshot.sendApplyNotifications()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `process start puts the stored theme on the payload the floor collects`() {
        storeTheme(MimirThemeId.HEL)

        seedCompanionThemeAtProcessStart(app)

        assertEquals(
            "the payload the display-4 floor collects for its cover, before Main has pushed",
            MimirThemeId.HEL,
            MimirThemeId.fromStorageKey(CompanionState.payload.value.themeId),
        )
    }

    @Test
    fun `a fresh process holds the Bottom's cold start in the stored theme`() {
        storeTheme(MimirThemeId.HEL)

        seedCompanionThemeAtProcessStart(app)
        CompanionState.holdForColdStart(timeoutMs = 60_000L)

        val hold = CompanionState.renderPayload.value.payload
        assertTrue("fixture: the payload on screen is the cold-start hold", hold.isLoading && hold.mode == CompanionMode.IDLE)
        assertEquals(
            "the cold-start hold the Presentation's cover wears, in a process Main has not pushed to",
            MimirThemeId.HEL,
            MimirThemeId.fromStorageKey(hold.themeId),
        )
    }

    @Test
    fun `the restore guard in a process Main has not pushed to grounds itself in the stored theme`() {
        assertNotEquals("fixture: Hel and Default must ground the guard differently", groundOf(MimirThemeId.HEL), groundOf(MimirThemeId.DEFAULT))
        storeTheme(MimirThemeId.HEL)
        seedCompanionThemeAtProcessStart(app)

        withActivityContext {
            val reclaim = reclaimOnDisplay(Display.DEFAULT_DISPLAY)
            val controller = Robolectric.buildActivity(CompanionReturnActivity::class.java, restoreGuardIntent(reclaim)).create()
            try {
                val guard = controller.get()
                assertFalse("fixture: the guard reached its restore branch instead of failing closed", guard.isFinishing)
                assertEquals(
                    "the restore guard's window ground, read once in onCreate",
                    groundOf(MimirThemeId.HEL),
                    (guard.window.decorView.background as ColorDrawable).color,
                )
            } finally {
                controller.destroy()
            }
        }
    }

    @Test
    fun `the reclaim hold in a process Main has not pushed to wears the stored theme`() {
        storeTheme(MimirThemeId.HEL)
        seedCompanionThemeAtProcessStart(app)

        reclaimOnDisplay(Display.DEFAULT_DISPLAY)

        val hold = CompanionState.renderPayload.value.payload
        assertTrue("fixture: the reclaim ran the display controller's hold", CompanionState.isReclaimHold(hold))
        assertEquals(
            "the reclaim hold the Presentation's cover wears",
            MimirThemeId.HEL,
            MimirThemeId.fromStorageKey(hold.themeId),
        )
    }

    @Test
    fun `process start loads the appearance overrides the Bottom's palette resolves`() {
        storeTheme(MimirThemeId.HEL)
        check(
            appearancePrefs().edit()
                .putString("appearanceAccentTemp.${MimirThemeId.HEL.name}", MimirAccentTemp.WARMER.name)
                .commit(),
        )
        val authoredAccent = MimirThemePalette.forId(MimirThemeId.HEL).accent
        val warmerAccent = MimirAccentTemp.WARMER.shift(authoredAccent)
        assertNotEquals("fixture: Warmer must move Hel's accent", authoredAccent, warmerAccent)

        seedCompanionThemeAtProcessStart(app)

        val bottomTheme = MimirThemeId.fromStorageKey(CompanionState.payload.value.themeId)
        assertEquals(
            "the accent the floor's sigil and the Presentation's covers paint, resolved as MimirTheme resolves it, before Main loads",
            warmerAccent,
            MimirThemePalette.forId(bottomTheme).withAppearanceOverrides().accent,
        )
    }

    @Test
    fun `Main's pushes win over the process-start theme, whichever lands first`() {
        storeTheme(MimirThemeId.HEL)
        val mainPush = CompanionPayload(
            mode = CompanionMode.HOME,
            themeId = MimirThemeId.BALDR.storageKey,
            statsLabel = "6 consoles",
        )

        seedCompanionThemeAtProcessStart(app)
        CompanionState.set(mainPush)
        assertEquals("Main's first push replaces the process-start payload", mainPush, CompanionState.payload.value)

        seedCompanionThemeAtProcessStart(app)
        assertEquals("a seed after Main's push leaves the push untouched", mainPush, CompanionState.payload.value)

        CompanionState.holdForColdStart(timeoutMs = 60_000L)
        assertEquals(
            "a hold after Main's push wears Main's theme",
            MimirThemeId.BALDR,
            MimirThemeId.fromStorageKey(CompanionState.payload.value.themeId),
        )
    }

    /**
     * The seed moves the appearance file's read from `MainActivity.onCreate` to `MimirApplication.onCreate`, on the main
     * thread. The file's cold load is timed on the bytes of a worst-case file, every key the four stores that share it can
     * write. Each timed read opens a fresh copy, so it pays SharedPreferencesImpl's whole cold load: the loader thread, the
     * file read and the XML parse. Production waits less, because `MimirPrefsPrefetch` starts this file's load at the top
     * of `Application.onCreate`. The whole helper is then timed once on the real file in memory: three loads and the seed.
     * This is the JVM, not the Thor; the device number is the process-start log's `readUs`.
     */
    @Test
    fun `the process-start read of a worst-case appearance file costs well under a frame`() {
        val worstCase = worstCaseAppearance()
        val fixture = "mimir_theme_seed_cost_fixture"
        val fixtureEditor = app.getSharedPreferences(fixture, Context.MODE_PRIVATE).edit()
        worstCase.forEach { (key, value) -> fixtureEditor.putString(key, value) }
        assertTrue("fixture: the framework wrote the file", fixtureEditor.commit())
        val prefsDir = File(app.dataDir, "shared_prefs")
        val bytes = File(prefsDir, "$fixture.xml").readBytes()
        assertTrue("fixture: the worst-case file holds every key (${bytes.size} B)", bytes.size > 1_000)

        fun coldReadMicros(name: String): Long {
            File(prefsDir, "$name.xml").writeBytes(bytes)
            val start = System.nanoTime()
            val stored = app.getSharedPreferences(name, Context.MODE_PRIVATE).getString("themePreference", null)
            val elapsed = System.nanoTime() - start
            assertEquals("fixture: every timed read parsed the copied file", MimirThemeId.HEL.storageKey, stored)
            return elapsed / 1_000L
        }

        val first = coldReadMicros("mimir_theme_seed_cost_first")
        val reads = (0 until 25).map { coldReadMicros("mimir_theme_seed_cost_$it") }.sorted()
        val median = reads[reads.size / 2]

        val appearanceEditor = appearancePrefs().edit()
        worstCase.forEach { (key, value) -> appearanceEditor.putString(key, value) }
        check(appearanceEditor.commit())
        val helperStart = System.nanoTime()
        seedCompanionThemeAtProcessStart(app)
        val helperMicros = (System.nanoTime() - helperStart) / 1_000L
        assertEquals("fixture: the helper read the worst-case file", MimirThemeId.HEL, ThemeStore.themeId.value)

        println(
            "CompanionThemeSeedTest cost: file=${bytes.size} B; first cold read=$first us (includes JVM class loading); " +
                "median of ${reads.size} cold reads=$median us; min=${reads.first()} us; max=${reads.last()} us; " +
                "whole helper, file in memory, first call=$helperMicros us (includes JVM class loading)",
        )
        assertTrue("median cold read $median us must stay far under one 60 Hz frame (16667 us)", median < 16_667L)
    }

    /**
     * Only `MimirApplication.onCreate` runs before every component. The seed must precede the database gate's early
     * return, because a refused database still has the floor restarted on display 4, and `CompanionProcessRuntime.services`,
     * which starts the companion runtime and its recovery. Pinned as a bare call line, which the helper's declaration
     * cannot satisfy.
     */
    @Test
    fun `MimirApplication seeds the theme before the database gate can return and before the companion runtime starts`() {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirApplication.kt")
        assertTrue("MimirApplication.kt must be readable from the module dir", file.isFile)
        val lines = file.readLines().map(String::trim)
        val onCreate = lines.indexOf("override fun onCreate() {")
        val gate = lines.indexOfFirst { it.startsWith("DatabaseStartupGateState.failure?.let") }
        val gateReturn = if (gate < 0) -1 else gate + lines.drop(gate).indexOf("return")
        val services = lines.indexOf("CompanionProcessRuntime.services(this)")
        assertTrue(
            "fixture: onCreate ($onCreate), the gate's return ($gateReturn) and the runtime start ($services) are found in order",
            onCreate >= 0 && gate > onCreate && gateReturn > gate && services > gateReturn,
        )
        val seedCalls = lines.withIndex().filter { it.value == "seedCompanionThemeAtProcessStart(this)" }.map { it.index }
        assertEquals("MimirApplication.onCreate must call seedCompanionThemeAtProcessStart(this) once", 1, seedCalls.size)
        val seed = seedCalls.single()
        assertTrue("the seed must run inside onCreate", seed > onCreate)
        assertTrue("the seed must run before the database gate can return", seed < gateReturn)
        assertTrue("the seed must run before the companion runtime starts", seed < services)
    }

    /** Written to the file only, as a previous process left it; nothing loads the store here. */
    private fun storeTheme(id: MimirThemeId) {
        check(appearancePrefs().edit().putString("themePreference", id.storageKey).commit())
    }

    private fun appearancePrefs() = app.getSharedPreferences("mimir_rewrite_appearance", Context.MODE_PRIVATE)

    /**
     * With nothing stored, each load returns its process-wide holder to its initial value, which is what a process holds
     * before anything has read the file. The next test class in this JVM expects the same.
     */
    private fun resetAppearanceToUnloaded() {
        appearancePrefs().edit().clear().commit()
        ThemeStore.load(app)
        AppearanceOverrideStore.load(app)
        check(ThemeStore.themeId.value == MimirThemeId.DEFAULT)
        check(MimirAppearanceOverrides.accentStop == MimirAccentStop.CLASSIC_GOLD)
        check(MimirAppearanceOverrides.accentTemps.isEmpty() && MimirAppearanceOverrides.ringStops.isEmpty())
    }

    /** Every key the three stores that share `mimir_rewrite_appearance` can write. */
    private fun worstCaseAppearance(): Map<String, String> = buildMap {
        put("themePreference", MimirThemeId.HEL.storageKey)
        put("gamelistBackdrop", BackdropStore.DEFAULT.name)
        put("appearanceAccentStop", MimirAccentStop.ANTIQUE_BRONZE.name)
        MimirThemeId.entries.forEach { id ->
            put("appearanceFocusRing.${id.name}", MimirRingStop.CRIMSON.name)
            put("appearanceAccentTemp.${id.name}", MimirAccentTemp.WARMER.name)
        }
    }

    private fun groundOf(id: MimirThemeId): Int = MimirThemePalette.forId(id).surfaceBase.copy(alpha = 1f).toArgb()

    /**
     * A real runtime reclaim on [displayId], from a mirror presented there, with the display controller's own preparation:
     * `CompanionDisplayController.prepareForReclaim` publishes `CompanionState.holdForReclaim()` before the guard launches.
     */
    private fun reclaimOnDisplay(displayId: Int): ReclaimLease {
        val runtime = CompanionRuntimeCoordinator(CompanionRuntimeScheduler { _, _, _ -> RuntimeCancellation {} })
        CompanionReturnCoordinator.installRuntime(runtime)
        val port = ReclaimHoldingPort()
        assertTrue("fixture: renderer bound", runtime.bindRenderer(port))
        assertTrue("fixture: display available", runtime.displayAvailable(displayId))
        val render = (runtime.snapshot.value.disposition as SecondaryDisplayDisposition.Presenting).attempt
        assertTrue(
            "fixture: mirror presented",
            runtime.presentationShown(render.operationId, render.parentDispositionId, render.rendererOwnerId),
        )
        val reclaim = checkNotNull(runtime.explicitReclaim("secondary_home_resumed", PrimaryReturnOrigin("home", emptyMap())))
        assertEquals("fixture: the reclaim ran the display controller's preparation", listOf(reclaim), port.prepared)
        return reclaim
    }

    /** The Intent `CompanionDisplayController.launchRestoreGuard` builds for [lease]. */
    private fun restoreGuardIntent(lease: ReclaimLease): Intent =
        Intent(app, CompanionReturnActivity::class.java)
            .putExtra(CompanionReturnCoordinator.EXTRA_OPERATION_ID, lease.operationId.toString())
            .putExtra(CompanionReturnHostContract.EXTRA_LAUNCH_MODE, CompanionReturnHostContract.LAUNCH_MODE_RESTORE_ONLY)
            .putExtra(CompanionReturnHostContract.EXTRA_RESTORE_REASON, lease.reason)
            .putExtra(CompanionReturnHostContract.EXTRA_PRIMARY_ORIGIN_DESTINATION, lease.primaryOrigin.destination)
            .putStringArrayListExtra(
                CompanionReturnHostContract.EXTRA_PRIMARY_ORIGIN_KEYS,
                ArrayList(lease.primaryOrigin.arguments.keys),
            )
            .putStringArrayListExtra(
                CompanionReturnHostContract.EXTRA_PRIMARY_ORIGIN_VALUES,
                ArrayList(lease.primaryOrigin.arguments.values),
            )

    /**
     * Robolectric attaches every Activity to the Application's base `ContextImpl`, a non-UI context, so the guard's
     * `display?.displayId` throws. On a device an Activity's context is an ACTIVITY context and that read returns its
     * display. Marked that way for [block] only, as `CompanionSecondaryHomeThemeTest` does for the floor.
     */
    private fun withActivityContext(block: () -> Unit) {
        val base = app.baseContext
        val originalType = ReflectionHelpers.getField<Int>(base, "mContextType")
        ReflectionHelpers.setField(
            base,
            "mContextType",
            ReflectionHelpers.getStaticField<Int>(base.javaClass, "CONTEXT_TYPE_ACTIVITY"),
        )
        try {
            block()
        } finally {
            ReflectionHelpers.setField(base, "mContextType", originalType)
        }
    }

    /** Renders nothing. Its reclaim preparation is `CompanionDisplayController`'s: the reclaim hold. */
    private class ReclaimHoldingPort : CompanionHostPort {
        override val rendererOwnerId: RendererOwnerId = RendererOwnerId.create()
        val prepared = mutableListOf<ReclaimLease>()

        override fun render(attempt: RenderAttempt) = Unit

        override fun dismissPresentation() = Unit

        override fun dismissPresentationAfterCover(hostOperationId: OperationId, delayMillis: Long) = Unit

        override fun prepareForReclaim(lease: ReclaimLease) {
            prepared += lease
            CompanionState.holdForReclaim()
        }

        override fun launchRestoreGuard(lease: ReclaimLease): Boolean = true
    }
}
