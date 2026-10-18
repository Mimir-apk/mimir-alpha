package com.gruning.mimir.rewrite

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Display
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompanionReturnActivityRecreationTest {
    @After
    fun resetRuntime() {
        CompanionReturnCoordinator.resetTestOverrides()
    }

    @Test
    fun coordinatorPreparedHostRecreatesWithRealPresentationReclaimPending() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val display = assumeAvailable(
            "Requires a live presentation-category secondary display",
            discoverPresentationDisplay(context),
        )
        val targetPackage = assumeAvailable(
            "No harmless system utility can be launched on presentation display ${display.displayId}",
            discoverHarmlessSecondaryTarget(context, display.displayId),
        )

        val runtime = CompanionRuntimeCoordinator(HoldingScheduler())
        val origin = PrimaryReturnOrigin(
            destination = "library/recreate",
            arguments = mapOf("source" to "androidTest"),
        )
        CompanionReturnCoordinator.installRuntime(runtime)
        CompanionReturnCoordinator.installPrimaryOriginProvider { origin }
        assertTrue(runtime.displayAvailable(display.displayId))

        val rawHostOperationId = checkNotNull(
            CompanionReturnCoordinator.noteHostLaunchStarting(
                packageName = targetPackage,
                displayId = display.displayId,
                launchMode = CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_APP,
            ),
        ) { "Coordinator rejected a launch on discovered display ${display.displayId}" }
        val hostOperationId = checkNotNull(OperationId.parseOrNull(rawHostOperationId))
        val prepared = runtime.snapshot.value.disposition as? SecondaryDisplayDisposition.LaunchPending
            ?: throw AssertionError("Coordinator did not publish its prepared host lease: ${runtime.snapshot.value}")
        assertEquals(hostOperationId, prepared.lease.operationId)
        assertEquals(display.displayId, prepared.lease.displayId)
        assertEquals(targetPackage, prepared.lease.packageName)
        assertEquals(origin, prepared.lease.primaryOrigin)

        val scenario = ActivityScenario.launch<CompanionReturnActivity>(
            returnHostIntent(context, targetPackage, rawHostOperationId, origin),
            ActivityOptions.makeBasic().apply { launchDisplayId = display.displayId }.toBundle(),
        )
        var displayBackSent = false
        try {
            val yielded = awaitYielded(runtime, hostOperationId)
            assertEquals(display.displayId, yielded.lease?.displayId)
            assertEquals(targetPackage, yielded.lease?.packageName)
            assertEquals(
                LaunchSettlement(hostOperationId, LaunchSettlementKind.HOST_COVERED),
                runtime.snapshot.value.launchSettlement,
            )

            displayBackSent = true
            pressBackOnDisplay(display.displayId)
            val reclaim = awaitReclaimAfterSafeBack(
                runtime = runtime,
                scenario = scenario,
                hostOperationId = hostOperationId,
                targetPackage = targetPackage,
                displayId = display.displayId,
            )
            assertEquals(hostOperationId, reclaim.lease.parentHostOperationId)
            assertEquals(display.displayId, reclaim.lease.displayId)
            assertEquals(origin, reclaim.lease.primaryOrigin)
            assertTrue(runtime.recognizes(reclaim.lease.operationId))
            assertFalse(runtime.recognizes(hostOperationId))

            var original: CompanionReturnActivity? = null
            scenario.onActivity { activity ->
                original = activity
                assertEquals(display.displayId, activity.display?.displayId)
                assertFalse(activity.isFinishing)
            }
            val beforeRecreation = runtime.snapshot.value

            scenario.recreate()

            scenario.onActivity { recreated ->
                assertNotSame(original, recreated)
                assertEquals(display.displayId, recreated.display?.displayId)
                assertFalse(recreated.isFinishing)
            }
            val afterRecreation = runtime.snapshot.value
            val stillPending = afterRecreation.disposition as? SecondaryDisplayDisposition.ReclaimPending
                ?: throw AssertionError("Recreation lost the child reclaim: $afterRecreation")
            assertEquals(beforeRecreation.stateVersion, afterRecreation.stateVersion)
            assertEquals(reclaim.lease, stillPending.lease)
            assertTrue(runtime.recognizes(reclaim.lease.operationId))
            assertFalse(runtime.recognizes(hostOperationId))
        } finally {
            if (!displayBackSent && runtime.isYieldedFor(hostOperationId)) {
                runCatching { pressBackOnDisplay(display.displayId) }
            }
            scenario.close()
            runtime.displayRemoved()
        }
    }

    private fun discoverPresentationDisplay(context: Context): Display? =
        context.getSystemService(DisplayManager::class.java)
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { display ->
                display.displayId != Display.DEFAULT_DISPLAY &&
                    display.isValid &&
                    display.state == Display.STATE_ON
            }

    private fun discoverHarmlessSecondaryTarget(context: Context, displayId: Int): String? {
        val packageManager = context.packageManager
        val activityManager = context.getSystemService(ActivityManager::class.java)
        for (packageName in HarmlessSystemUtilityPackages) {
            val launchIntent = secondaryLaunchIntent(packageManager, packageName) ?: continue
            val activityInfo = packageManager.resolveActivity(
                launchIntent,
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo ?: continue
            val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            if (!activityInfo.enabled || !activityInfo.exported ||
                activityInfo.applicationInfo.flags and systemFlags == 0
            ) {
                continue
            }
            val requiredPermission = activityInfo.permission
            if (!requiredPermission.isNullOrBlank() &&
                packageManager.checkPermission(requiredPermission, context.packageName) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                continue
            }
            val allowed = runCatching {
                activityManager.isActivityStartAllowedOnDisplay(context, displayId, launchIntent)
            }.getOrDefault(false)
            if (allowed) return packageName
        }
        return null
    }

    private fun secondaryLaunchIntent(
        packageManager: PackageManager,
        packageName: String,
    ): Intent? = packageManager.getLaunchIntentForPackage(packageName)?.apply {
        flags = flags and Intent.FLAG_ACTIVITY_CLEAR_TASK.inv()
        flags = flags and Intent.FLAG_ACTIVITY_NEW_DOCUMENT.inv()
        flags = flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK.inv()
        flags = flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT.inv()
        flags = flags and Intent.FLAG_ACTIVITY_SINGLE_TOP.inv()
        flags = flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED.inv()
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }

    private fun returnHostIntent(
        context: Context,
        targetPackage: String,
        operationId: String,
        origin: PrimaryReturnOrigin,
    ): Intent {
        val arguments = origin.arguments.entries.toList()
        return Intent(context, CompanionReturnActivity::class.java)
            .putExtra(CompanionReturnHostContract.EXTRA_TARGET_PACKAGE, targetPackage)
            .putExtra(
                CompanionReturnHostContract.EXTRA_LAUNCH_MODE,
                CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_APP,
            )
            .putExtra(CompanionReturnCoordinator.EXTRA_OPERATION_ID, operationId)
            .putExtra(
                CompanionReturnHostContract.EXTRA_PRIMARY_ORIGIN_DESTINATION,
                origin.destination,
            )
            .putStringArrayListExtra(
                CompanionReturnHostContract.EXTRA_PRIMARY_ORIGIN_KEYS,
                ArrayList(arguments.map { it.key }),
            )
            .putStringArrayListExtra(
                CompanionReturnHostContract.EXTRA_PRIMARY_ORIGIN_VALUES,
                ArrayList(arguments.map { it.value }),
            )
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
    }

    private fun awaitYielded(
        runtime: CompanionRuntimeCoordinator,
        hostOperationId: OperationId,
    ): SecondaryDisplayDisposition.Yielded {
        val yielded = awaitDisposition(runtime, HostLaunchTimeoutMillis) { disposition ->
            disposition is SecondaryDisplayDisposition.Yielded &&
                disposition.lease?.operationId == hostOperationId
        } as? SecondaryDisplayDisposition.Yielded
        return yielded
            ?: throw AssertionError("Timed out waiting for real host coverage: ${runtime.snapshot.value}")
    }

    private fun awaitReclaimAfterSafeBack(
        runtime: CompanionRuntimeCoordinator,
        scenario: ActivityScenario<CompanionReturnActivity>,
        hostOperationId: OperationId,
        targetPackage: String,
        displayId: Int,
    ): SecondaryDisplayDisposition.ReclaimPending {
        val reclaim = awaitDisposition(runtime, HostReturnTimeoutMillis) { disposition ->
            disposition is SecondaryDisplayDisposition.ReclaimPending &&
                disposition.lease.parentHostOperationId == hostOperationId
        } as? SecondaryDisplayDisposition.ReclaimPending
        if (reclaim != null) return reclaim
        if (scenario.state == Lifecycle.State.RESUMED) {
            throw AssertionError(
                "Return host resumed without minting its child reclaim: ${runtime.snapshot.value}",
            )
        }
        Assume.assumeTrue(
            "Instrumentation could not safely return from $targetPackage on display $displayId with one BACK",
            false,
        )
        throw AssertionError("JUnit assumption did not abort")
    }

    private fun awaitDisposition(
        runtime: CompanionRuntimeCoordinator,
        timeoutMillis: Long,
        predicate: (SecondaryDisplayDisposition) -> Boolean,
    ): SecondaryDisplayDisposition? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            val disposition = runtime.snapshot.value.disposition
            if (predicate(disposition)) return disposition
            SystemClock.sleep(PollIntervalMillis)
        }
        return null
    }

    private fun pressBackOnDisplay(displayId: Int) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input -d $displayId keyevent KEYCODE_BACK")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { output ->
            val buffer = ByteArray(128)
            while (output.read(buffer) != -1) Unit
        }
    }

    private fun <T : Any> assumeAvailable(message: String, value: T?): T {
        if (value == null) {
            Assume.assumeTrue(message, false)
            throw AssertionError("JUnit assumption did not abort")
        }
        return value
    }

    private class HoldingScheduler : CompanionRuntimeScheduler {
        override fun schedule(
            delayMillis: Long,
            operationId: OperationId,
            action: () -> Unit,
        ): RuntimeCancellation = RuntimeCancellation {}
    }

    private companion object {
        private const val HostLaunchTimeoutMillis = 10_000L
        private const val HostReturnTimeoutMillis = 5_000L
        private const val PollIntervalMillis = 50L

        // These system utilities are inert at their launcher screen and require no user data mutation.
        private val HarmlessSystemUtilityPackages = listOf(
            "com.android.calculator2",
            "com.google.android.calculator",
            "com.android.deskclock",
            "com.google.android.deskclock",
            "com.android.settings",
        )
    }
}
