package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.rewrite.core.companionnative.SecondaryDisplaySnapshot
import com.gruning.mimir.rewrite.core.companionnative.SecondaryDisplaySelector
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = Application::class)
class SecondaryDisplaySelectorRobolectricTest {
    @Before
    fun setUp() {
        ShadowDisplayManager.reset()
    }

    @After
    fun tearDown() {
        ShadowDisplayManager.reset()
    }

    @Test
    fun realSelectorPublishesRobolectricPresentationAddAndRemoveCallbacks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val selector = SecondaryDisplaySelector(context)
        val observed = mutableListOf<List<Int>>()
        val subscription = selector.addListener { snapshot: SecondaryDisplaySnapshot ->
            observed += snapshot.eligibleIds
        }

        try {
            val displayManager = context.getSystemService(DisplayManager::class.java)
            val displayId = addPresentationDisplay(displayManager)

            assertEquals(listOf(displayId), selector.snapshot.value.eligibleIds)
            assertEquals(displayId, selector.snapshot.value.selectedId)
            assertTrue(observed.last() == listOf(displayId))

            ShadowDisplayManager.removeDisplay(displayId)
            ShadowLooper.idleMainLooper()

            assertEquals(emptyList<Int>(), selector.snapshot.value.eligibleIds)
            assertEquals(null, selector.snapshot.value.selectedId)
            assertEquals(emptyList<Int>(), observed.last())
        } finally {
            subscription.close()
            selector.close()
        }
    }

    @Test
    fun realSelectorCallbackDrivesRealControllerPresentationAttempt() {
        val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = activityController.get()
        val selector = SecondaryDisplaySelector(activity)
        val runtime = CompanionRuntimeCoordinator(
            CompanionRuntimeScheduler { _, _, _ -> RuntimeCancellation {} },
        )
        val controller = CompanionDisplayController(
            activity = activity,
            secondaryDisplaySelector = selector,
            runtimeCoordinator = runtime,
        )
        try {
            controller.start()

            val displayManager = activity.getSystemService(DisplayManager::class.java)
            val displayId = addPresentationDisplay(displayManager)

            assertEquals(displayId, selector.snapshot.value.selectedId)
            assertTrue(
                "the real selector callback must drive the controller to a showing Presentation",
                controller.secondaryWindow() != null,
            )

            ShadowDisplayManager.removeDisplay(displayId)
            ShadowLooper.idleMainLooper()

            assertEquals(emptyList<Int>(), selector.snapshot.value.eligibleIds)
            assertNull(controller.secondaryWindow())
            assertTrue(runtime.snapshot.value.disposition is SecondaryDisplayDisposition.NoDisplay)
        } finally {
            controller.stop()
            selector.close()
            activityController.pause().stop().destroy()
        }
    }

    @Test
    fun realSelectorAndControllerCoalesceDisplayReplacementWithoutTransientNoDisplayRequirement() {
        val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = activityController.get()
        val selector = SecondaryDisplaySelector(activity)
        val runtime = CompanionRuntimeCoordinator(
            CompanionRuntimeScheduler { _, _, _ -> RuntimeCancellation {} },
        )
        val controller = CompanionDisplayController(
            activity = activity,
            secondaryDisplaySelector = selector,
            runtimeCoordinator = runtime,
        )
        try {
            controller.start()
            val displayManager = activity.getSystemService(DisplayManager::class.java)
            val displayA = addPresentationDisplay(displayManager)
            val windowA = requireNotNull(controller.secondaryWindow())

            val displayB = addPresentationDisplay(displayManager)
            assertEquals(
                "adding B while A remains eligible must preserve A's selected identity",
                displayA,
                selector.snapshot.value.selectedId,
            )

            // Removing A lets the production selector choose B directly; no intermediate None is required.
            ShadowDisplayManager.removeDisplay(displayA)
            ShadowLooper.idleMainLooper()

            assertEquals(listOf(displayB), selector.snapshot.value.eligibleIds)
            assertEquals(displayB, selector.snapshot.value.selectedId)
            val windowB = requireNotNull(controller.secondaryWindow())
            assertNotSame("replacement must not retain A's dismissed window", windowA, windowB)

            ShadowDisplayManager.removeDisplay(displayB)
            ShadowLooper.idleMainLooper()
            assertNull(controller.secondaryWindow())
            assertTrue(runtime.snapshot.value.disposition is SecondaryDisplayDisposition.NoDisplay)
        } finally {
            controller.stop()
            selector.close()
            activityController.pause().stop().destroy()
        }
    }

    private fun addPresentationDisplay(displayManager: DisplayManager): Int {
        // Robolectric 4.14.1 exposes addDisplay(String, int); its bytecode forwards the int to
        // DisplayInfo.type. The selector's public eligibility is established separately by this flag.
        val displayId = ShadowDisplayManager.addDisplay("+land", 2)
        Shadows.shadowOf(requireNotNull(displayManager.getDisplay(displayId)))
            .setFlags(Display.FLAG_PRESENTATION)
        ShadowLooper.idleMainLooper()
        return displayId
    }
}
