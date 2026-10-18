package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.Display
import android.view.View
import android.widget.Toast
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

/**
 * Round 5 (5b): the ᛗ router's failure toasts were made from the process context, which Android places on the default
 * display, so a command the player pressed on the Bottom's menu reported its failure on Main.
 *
 * A text toast is placed by the display of the context it was made with (`Toast.show()` hands `Context.getDisplayId()`
 * to the notification service), so each test reads that display from the context its toast was made with. Robolectric's
 * Toast shadow drops that context; [ContextRecordingShadowToast] keeps it.
 *
 * Every failure path runs through the real router: the Bottom's own Quick launch picker that could not open (policy
 * O5 answers the Bottom menu's Quick launch with that picker, never an Activity start), a global command from Main's menu
 * whose Activity start is refused (`completePrimaryDispatch`), and a Tabs hold whose Android Recents does not open (the
 * SYSTEM_TABS branch, which toasts from Main after an IO hop). Each test first checks that the command failed and toasted
 * its copy, so a red on the last assertion is about the display alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE,
    sdk = [30],
    application = Application::class,
    shadows = [ContextRecordingShadowToast::class],
)
class MButtonFailureToastDisplayTest {

    private lateinit var app: Application
    private lateinit var scope: CoroutineScope
    private var bottomDisplayId = Display.INVALID_DISPLAY

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        bottomDisplayId = ShadowDisplayManager.addDisplay("+land", 2)
        ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() {
        scope.cancel()
        ShadowDisplayManager.removeDisplay(bottomDisplayId)
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `a Quick launch the Bottom menu could not open is reported on the Bottom`() {
        val overlay = Overlay(CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, bottomDisplayId))
        // O5: the Bottom's picker refuses, as it does while a launch holds the lock. The router's context starts activities,
        // so a press that fell back to an Activity start would complete as accepted rather than refused.
        val picker = BottomPicker(BottomQuickLaunchOpenResult(opened = false, refusal = "launch_locked"))
        startRouter(app, overlay, primaryActivityStopped = false, bottomQuickLaunch = picker)

        val requestId = press(overlay, CompanionOverlayRole.SECONDARY_COMPANION, QuickMenuItem.QUICK_LAUNCH)

        assertEquals("the Bottom's own picker was asked to open, once", 1, picker.opens)
        assertEquals("the menu completed as refused", listOf(requestId to false), overlay.completions)
        assertEquals(QuickLaunchFailureCopy, ShadowToast.getTextOfLatestToast())
        assertEquals("made from a context on the Bottom display", bottomDisplayId, latestToastDisplayId())
    }

    @Test
    fun `a Tabs hold on the Bottom menu whose Android Recents does not open is reported on the Bottom`() {
        val overlay = Overlay(CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, bottomDisplayId))
        val picker = BottomPicker.unused()
        startRouter(app, overlay, primaryActivityStopped = false, bottomQuickLaunch = picker)

        val requestId = press(overlay, CompanionOverlayRole.SECONDARY_COMPANION, QuickMenuItem.SYSTEM_TABS)
        awaitToast()

        assertEquals("the Bottom's Quick launch picker is not this command's route", 0, picker.opens)
        assertEquals("the menu completed as refused", listOf(requestId to false), overlay.completions)
        assertEquals(RecentsFailureCopy, ShadowToast.getTextOfLatestToast())
        assertEquals("made from a context on the Bottom display", bottomDisplayId, latestToastDisplayId())
    }

    @Test
    fun `a Quick launch Main's menu could not open stays on Main`() {
        val context = refusingContext()
        val overlay = Overlay(CompanionOverlayTarget(CompanionOverlayRole.PRIMARY_SINGLE, Display.DEFAULT_DISPLAY))
        val picker = BottomPicker.unused()
        startRouter(context, overlay, primaryActivityStopped = true, bottomQuickLaunch = picker)

        val requestId = press(overlay, CompanionOverlayRole.PRIMARY_SINGLE, QuickMenuItem.QUICK_LAUNCH)

        assertEquals("Main's menu never opens the Bottom's picker", 0, picker.opens)
        assertEquals("the panel and the Activity start were refused", listOf(requestId to false), overlay.completions)
        assertEquals(QuickLaunchFailureCopy, ShadowToast.getTextOfLatestToast())
        assertSame("made from the router's own context, as before", context, latestToastContext())
        assertEquals(Display.DEFAULT_DISPLAY, latestToastDisplayId())
    }

    @Test
    fun `the menu a command came from decides, not the display the overlay is on when the toast is made`() {
        val context = refusingContext()
        // A command from Main's single-screen menu that is handled after the overlay was retargeted to a new Bottom.
        val overlay = Overlay(CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, bottomDisplayId))
        val picker = BottomPicker.unused()
        startRouter(context, overlay, primaryActivityStopped = true, bottomQuickLaunch = picker)

        val requestId = press(overlay, CompanionOverlayRole.PRIMARY_SINGLE, QuickMenuItem.QUICK_LAUNCH)

        assertEquals("Main's menu never opens the Bottom's picker", 0, picker.opens)
        assertEquals("the panel and the Activity start were refused", listOf(requestId to false), overlay.completions)
        assertEquals(QuickLaunchFailureCopy, ShadowToast.getTextOfLatestToast())
        assertSame("made from the router's own context", context, latestToastContext())
    }

    private fun startRouter(
        context: Context,
        overlay: Overlay,
        primaryActivityStopped: Boolean,
        bottomQuickLaunch: MButtonBottomQuickLaunchGateway,
    ) {
        MButtonProcessActionRouter(
            context = context,
            overlay = overlay,
            systemRecents = UnopenableRecents,
            primaryPanel = MButtonPrimaryPanelGateway { false },
            bottomQuickLaunch = bottomQuickLaunch,
            isPrimaryActivityStopped = { primaryActivityStopped },
            scope = scope,
        )
    }

    private fun press(overlay: Overlay, role: CompanionOverlayRole, item: QuickMenuItem): UUID {
        val requestId = UUID.randomUUID()
        assertTrue(
            "the menu action was emitted",
            overlay.actionFlow.tryEmit(CompanionOverlayAction.QuickMenu(requestId, role, item)),
        )
        return requestId
    }

    /** The router's process context, with every Activity start refused. */
    private fun refusingContext(): Context = object : ContextWrapper(app) {
        override fun startActivity(intent: Intent?, options: Bundle?) {
            throw SecurityException("test: Activity start refused")
        }
    }

    /** The SYSTEM_TABS branch toasts from Main after an IO hop: run Main's queue until a toast is shown, bounded. */
    private fun awaitToast() {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (ShadowToast.shownToastCount() == 0 && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
    }

    private fun latestToastContext(): Context {
        val toast = checkNotNull(ShadowToast.getLatestToast()) { "no toast was shown" }
        return Shadow.extract<ContextRecordingShadowToast>(toast).madeWith
            ?: throw AssertionError("the toast recorded no context")
    }

    /** `Context.getDisplayId()` is hidden API, so it is read from the framework jar Robolectric runs. */
    private fun latestToastDisplayId(): Int =
        Context::class.java.getMethod("getDisplayId").invoke(latestToastContext()) as Int

    /** The Bottom's own Quick launch picker (O5), counting the router's requests to open it. */
    private class BottomPicker(private val result: BottomQuickLaunchOpenResult) : MButtonBottomQuickLaunchGateway {
        var opens = 0
            private set

        override fun open(): BottomQuickLaunchOpenResult {
            opens += 1
            return result
        }

        companion object {
            /** For a command that must not reach the picker; the test asserts it was never asked. */
            fun unused() = BottomPicker(BottomQuickLaunchOpenResult(opened = false, refusal = "test: not this command's route"))
        }
    }

    private object UnopenableRecents : MButtonSystemRecentsGateway {
        override fun systemRecentsAccessStatus(): PrivilegedUtilityStatus = PrivilegedUtilityStatus.READY
        override fun requestSystemRecentsAccess(expectedStatus: PrivilegedUtilityStatus): Boolean = false
        override fun openSystemRecents(): Boolean = false
    }

    private class Overlay(target: CompanionOverlayTarget?) : CompanionOverlayController {
        val actionFlow = MutableSharedFlow<CompanionOverlayAction>(extraBufferCapacity = 8)
        val completions = mutableListOf<Pair<UUID, Boolean>>()

        override val activeTarget: StateFlow<CompanionOverlayTarget?> = MutableStateFlow(target)
        override val quickMenuOpen: StateFlow<Boolean> = MutableStateFlow(false)
        override val overlayPermissionMissing: StateFlow<Boolean> = MutableStateFlow(false)
        override val actions: SharedFlow<CompanionOverlayAction> = actionFlow

        override fun completeQuickMenu(requestId: UUID, accepted: Boolean) {
            completions += requestId to accepted
        }

        override fun targetOverlay(target: CompanionOverlayTarget) = Unit
        override fun dismiss() = Unit
        override fun setVisible(visible: Boolean) = Unit
        override fun setPrimaryIdleHidden(hidden: Boolean) = Unit
        override fun setPrimaryIdleInteractionObserver(observer: (() -> Unit)?) = Unit
        override fun setOverlayTheme(theme: CompanionOverlayTheme) = Unit
        override fun setDimmed(dimmed: Boolean) = Unit
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

    private companion object {
        const val QuickLaunchFailureCopy = "Mimir couldn’t open Quick launch from the background."
        const val RecentsFailureCopy = "Android app switching couldn’t open. Review access in Settings › Controls."
    }
}

/** Robolectric's Toast shadow, keeping the context each toast is made with (its constructor shadow drops it). */
@Implements(Toast::class)
class ContextRecordingShadowToast : ShadowToast() {
    var madeWith: Context? = null
        private set

    @Implementation
    override fun __constructor__(context: Context) {
        madeWith = context
    }
}
