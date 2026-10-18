package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayAction
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTarget
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTheme
import com.gruning.mimir.rewrite.core.companionnative.NativeOverlayInteractionObserver
import com.gruning.mimir.rewrite.core.companionnative.PanelAttachResult
import com.gruning.mimir.rewrite.core.companionnative.PanelLeaseId
import com.gruning.mimir.rewrite.core.companionnative.PanelRejectReason
import com.gruning.mimir.rewrite.core.companionnative.PanelRevocationReason
import com.gruning.mimir.rewrite.core.companionnative.QuickMenuItem
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

/**
 * the Bottom ᛗ menu's Quick launch opens the picker on the Bottom, and Main is not touched.
 *
 * Driven through the real [MButtonProcessActionRouter] with the real [MButtonIntentAuthority] behind it, so "no
 * MainActivity start" is read off Robolectric's record of started activities, not off a stand-in for the authority. The
 * controls show that same record catching a start where one still belongs, from the Bottom menu included.
 *
 * A picker that cannot open is reported once, on the Bottom where the menu was pressed. The toast's display is
 * read from the context it was made with, which [ContextRecordingShadowToast] keeps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE,
    sdk = [30],
    application = Application::class,
    shadows = [ContextRecordingShadowToast::class],
)
class BottomQuickLaunchRouteTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val application: Application get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        scope.cancel()
        MButtonNavigationCommands.pending.value?.let { MButtonNavigationCommands.acknowledge(it.id) }
        while (shadowOf(application).nextStartedActivity != null) Unit
    }

    @Test
    fun `the Bottom coin's Quick launch opens the Bottom picker and starts no MainActivity`() {
        val router = RouterHarness(primaryStopped = true)

        val request = router.press(CompanionOverlayRole.SECONDARY_COMPANION, QuickMenuItem.QUICK_LAUNCH)

        assertEquals(listOf("bottom-picker"), router.calls)
        assertNull("the Bottom's Quick launch must start no Activity", shadowOf(application).nextStartedActivity)
        assertNull("nor leave a command for MainActivity to pick up", MButtonNavigationCommands.pending.value)
        assertEquals("the menu collapses onto the opened picker", listOf(request to true), router.overlay.completions)
    }

    @Test
    fun `the Bottom menu's Utility with no Utility model bound never starts MainActivity, with Mimir in front or not`() {
        // Utility never falls back to Main. This used to be the one case that did (no model, no game); the press is
        // now refused where it was made, with one toast on the Bottom, and Main is left alone.
        val bottomDisplayId = ShadowDisplayManager.addDisplay("+land", 2)
        ShadowLooper.idleMainLooper()
        try {
            for (primaryStopped in listOf(true, false)) {
                ShadowToast.reset()
                val router = RouterHarness(
                    primaryStopped = primaryStopped,
                    menuTarget = CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, bottomDisplayId),
                )

                val request = router.press(CompanionOverlayRole.SECONDARY_COMPANION, QuickMenuItem.UTILITY)

                val what = "primaryStopped=$primaryStopped"
                assertEquals("$what: the window was asked first and found no model", listOf("bottom-utility"), router.calls)
                assertNull("$what: nothing is started on Main", shadowOf(application).nextStartedActivity)
                assertNull("$what: nor left for MainActivity to pick up", MButtonNavigationCommands.pending.value)
                assertEquals(what, listOf(request to false), router.overlay.completions)
                assertEquals("$what: one toast", 1, ShadowToast.shownToastCount())
                assertEquals("$what: on the Bottom", bottomDisplayId, latestToastDisplayId())
            }
        } finally {
            ShadowDisplayManager.removeDisplay(bottomDisplayId)
            ShadowLooper.idleMainLooper()
        }
    }

    @Test
    fun `control - the single-screen menu's Quick launch keeps its panel and Activity routes`() {
        val overApp = RouterHarness(primaryStopped = true)
        overApp.press(CompanionOverlayRole.PRIMARY_SINGLE, QuickMenuItem.QUICK_LAUNCH)
        assertEquals(listOf("primary-panel:QUICK_LAUNCH"), overApp.calls)
        assertNull(shadowOf(application).nextStartedActivity)

        val overMimir = RouterHarness(primaryStopped = false)
        overMimir.press(CompanionOverlayRole.PRIMARY_SINGLE, QuickMenuItem.QUICK_LAUNCH)
        assertEquals(emptyList<String>(), overMimir.calls)
        assertNotNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `the Bottom coin's Utility opens the Bottom window and starts no MainActivity, whichever window is in front`() {
        // #267 : the window is the answer whichever window is on top of the Bottom, so the press is the
        // same with a dual-screen emulator's game running as with nothing. Main is never touched. A press the Presentation carried
        // (the window could not be lent and no game runs) answers opened too, and is just as free of Main.
        val answers = listOf(
            BottomUtilityOpenResult(opened = true),
            BottomUtilityOpenResult(opened = true, refusal = PresentationFallbackPrefix + "overlay_permission_missing"),
        )
        for (answer in answers) {
            for (primaryStopped in listOf(false, true)) {
                val router = RouterHarness(primaryStopped = primaryStopped, utilityResult = answer)

                val request = router.press(CompanionOverlayRole.SECONDARY_COMPANION, QuickMenuItem.UTILITY)

                val what = "$answer primaryStopped=$primaryStopped"
                assertEquals(what, listOf("bottom-utility"), router.calls)
                assertNull("$what: the Bottom's Utility must start no Activity", shadowOf(application).nextStartedActivity)
                assertNull("$what: nor leave a command for MainActivity to pick up", MButtonNavigationCommands.pending.value)
                assertEquals("$what: the menu collapses onto the opened Utility", listOf(request to true), router.overlay.completions)
            }
        }
    }

    @Test
    fun `a Bottom Utility window that could not be shown starts nothing on Main and is reported once on the Bottom`() {
        val bottomDisplayId = ShadowDisplayManager.addDisplay("+land", 2)
        ShadowLooper.idleMainLooper()
        try {
            for (refusal in listOf("model_declined", "overlay_permission_missing")) {
                ShadowToast.reset()
                val router = RouterHarness(
                    primaryStopped = true,
                    menuTarget = CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, bottomDisplayId),
                    utilityResult = BottomUtilityOpenResult(opened = false, refusal = refusal),
                )

                val request = router.press(CompanionOverlayRole.SECONDARY_COMPANION, QuickMenuItem.UTILITY)

                assertEquals(refusal, listOf("bottom-utility"), router.calls)
                assertNull("$refusal: a refused window is not a reason to open Main", shadowOf(application).nextStartedActivity)
                assertEquals(refusal, listOf(request to false), router.overlay.completions)
                assertEquals("$refusal: one toast", 1, ShadowToast.shownToastCount())
                assertEquals("$refusal: made from a context on the Bottom display", bottomDisplayId, latestToastDisplayId())
            }
        } finally {
            ShadowDisplayManager.removeDisplay(bottomDisplayId)
            ShadowLooper.idleMainLooper()
        }
    }

    @Test
    fun `control - the single-screen menu's Utility keeps its panel and Activity routes`() {
        val overApp = RouterHarness(primaryStopped = true)
        overApp.press(CompanionOverlayRole.PRIMARY_SINGLE, QuickMenuItem.UTILITY)
        assertEquals(listOf("primary-panel:UTILITY"), overApp.calls)
        assertNull(shadowOf(application).nextStartedActivity)

        val overMimir = RouterHarness(primaryStopped = false)
        overMimir.press(CompanionOverlayRole.PRIMARY_SINGLE, QuickMenuItem.UTILITY)
        assertEquals("Main's own menu never asks the Bottom's window", emptyList<String>(), overMimir.calls)
        assertNotNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `a refused Bottom picker leaves the menu open, starts nothing and is reported once on the Bottom`() {
        val bottomDisplayId = ShadowDisplayManager.addDisplay("+land", 2)
        ShadowLooper.idleMainLooper()
        try {
            val router = RouterHarness(
                primaryStopped = true,
                bottomResult = BottomQuickLaunchOpenResult(opened = false, refusal = "launch_locked"),
                // The Bottom ᛗ, whose menu was pressed, is mounted on the Bottom display.
                menuTarget = CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, bottomDisplayId),
            )

            val request = router.press(CompanionOverlayRole.SECONDARY_COMPANION, QuickMenuItem.QUICK_LAUNCH)

            assertEquals(listOf("bottom-picker"), router.calls)
            assertNull(shadowOf(application).nextStartedActivity)
            assertEquals(listOf(request to false), router.overlay.completions)
            assertEquals("a picker that could not open is reported with one toast", 1, ShadowToast.shownToastCount())
            assertEquals("made from a context on the Bottom display", bottomDisplayId, latestToastDisplayId())
        } finally {
            ShadowDisplayManager.removeDisplay(bottomDisplayId)
            ShadowLooper.idleMainLooper()
        }
    }

    /** `Context.getDisplayId()` is hidden API, so it is read from the framework jar Robolectric runs. */
    private fun latestToastDisplayId(): Int {
        val toast = checkNotNull(ShadowToast.getLatestToast()) { "no toast was shown" }
        val madeWith = checkNotNull(Shadow.extract<ContextRecordingShadowToast>(toast).madeWith) {
            "the toast recorded no context"
        }
        return Context::class.java.getMethod("getDisplayId").invoke(madeWith) as Int
    }

    private inner class RouterHarness(
        primaryStopped: Boolean,
        bottomResult: BottomQuickLaunchOpenResult = BottomQuickLaunchOpenResult(opened = true),
        menuTarget: CompanionOverlayTarget? = null,
        /** The Bottom Utility window's answer; null leaves it unbound, as it is before any host has made the Utility model. */
        utilityResult: BottomUtilityOpenResult? = null,
    ) {
        val calls = mutableListOf<String>()
        val overlay = RecordingOverlayController(menuTarget)

        init {
            MButtonProcessActionRouter(
                context = application,
                overlay = overlay,
                systemRecents = UnusedSystemRecents,
                primaryPanel = MButtonPrimaryPanelGateway { command ->
                    calls += "primary-panel:$command"
                    true
                },
                bottomQuickLaunch = MButtonBottomQuickLaunchGateway {
                    calls += "bottom-picker"
                    bottomResult
                },
                isPrimaryActivityStopped = { primaryStopped },
                scope = scope,
                bottomUtility = MButtonBottomUtilityGateway {
                    calls += "bottom-utility"
                    utilityResult ?: BottomUtilityOpenResult(opened = false, refusal = BottomUtilityHostUnbound)
                },
            )
        }

        fun press(role: CompanionOverlayRole, item: QuickMenuItem): UUID {
            val requestId = UUID.randomUUID()
            assertTrue(
                "the router must be listening to the menu",
                overlay.emit(CompanionOverlayAction.QuickMenu(requestId, role, item)),
            )
            shadowOf(Looper.getMainLooper()).idle()
            return requestId
        }
    }
}

private object UnusedSystemRecents : MButtonSystemRecentsGateway {
    override fun systemRecentsAccessStatus(): PrivilegedUtilityStatus = error("no test here opens Android Recents")

    override fun requestSystemRecentsAccess(expectedStatus: PrivilegedUtilityStatus): Boolean =
        error("no test here opens Android Recents")

    override fun openSystemRecents(): Boolean = error("no test here opens Android Recents")
}

private class RecordingOverlayController(target: CompanionOverlayTarget? = null) : CompanionOverlayController {
    private val menuActions = MutableSharedFlow<CompanionOverlayAction>(extraBufferCapacity = 8)
    val completions = mutableListOf<Pair<UUID, Boolean>>()

    fun emit(action: CompanionOverlayAction): Boolean =
        menuActions.subscriptionCount.value > 0 && menuActions.tryEmit(action)

    override fun targetOverlay(target: CompanionOverlayTarget) = Unit
    override fun dismiss() = Unit
    override fun setVisible(visible: Boolean) = Unit
    override fun setPrimaryIdleHidden(hidden: Boolean) = Unit
    override fun setPrimaryIdleInteractionObserver(observer: (() -> Unit)?) = Unit
    override val activeTarget: StateFlow<CompanionOverlayTarget?> = MutableStateFlow(target)
    override val quickMenuOpen: StateFlow<Boolean> = MutableStateFlow(false)
    override fun setOverlayTheme(theme: CompanionOverlayTheme) = Unit
    override fun setDimmed(dimmed: Boolean) = Unit
    override val overlayPermissionMissing: StateFlow<Boolean> = MutableStateFlow(false)
    override val actions: SharedFlow<CompanionOverlayAction> = menuActions

    override fun completeQuickMenu(requestId: UUID, accepted: Boolean) {
        completions += requestId to accepted
    }

    override fun hasOverlayPermission(): Boolean = true
    override fun requestOverlayPermission() = Unit
    override fun reconcile() = Unit
    override fun setNativeOverlayInteractionObserver(observer: NativeOverlayInteractionObserver?) = Unit

    override fun attachSecondaryPanel(
        view: View,
        onRevoked: (PanelLeaseId, PanelRevocationReason) -> Unit,
    ): PanelAttachResult = PanelAttachResult.Rejected(PanelRejectReason.DISPLAY_UNAVAILABLE)

    override fun attachPrimaryPanel(
        view: View,
        onRevoked: (PanelLeaseId, PanelRevocationReason) -> Unit,
    ): PanelAttachResult = PanelAttachResult.Rejected(PanelRejectReason.DISPLAY_UNAVAILABLE)

    override fun setPrimaryPanelBrightness(fraction: Float?) = Unit
}
