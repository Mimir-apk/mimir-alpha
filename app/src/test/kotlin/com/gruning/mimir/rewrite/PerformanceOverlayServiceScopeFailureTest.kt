package com.gruning.mimir.rewrite

import java.io.File
import kotlinx.coroutines.CoroutineName
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The performance HUD must not be able to kill the launcher.
 *
 * Exactly the defect fixed in `TweakerBridge` on 2026-09-06, in a second place: `serviceScope` was
 * `CoroutineScope(SupervisorJob() + Dispatchers.IO)` with no handler. `SupervisorJob` stops a sibling
 * failure cancelling the scope; it does NOT stop the exception, and `PerformanceOverlayService`
 * declares no `android:process`, so it shares the launcher's. Every job on that scope reads something
 * absent-able -- the daemon's telemetry socket, Usage Access, and the privileged
 * `dumpsys SurfaceFlinger` frame-rate route added the same day.
 *
 * See `TweakerBridgeOwnerScopeFailureTest` for the sibling pin for what an unhandled throw on a
 * process-lifetime scope also does to the unit suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class PerformanceOverlayServiceScopeFailureTest {

    @Test
    fun `the handler absorbs a background failure instead of letting it escape`() {
        val service = Robolectric.buildService(PerformanceOverlayService::class.java).get()
        val handler = service.serviceScopeFailureHandler

        // A handler that rethrows, or that is absent, is how the process dies.
        handler.handleException(
            CoroutineName("overlay-probe") + handler,
            IllegalStateException("privileged utility absent, as it is on every unit-test JVM"),
        )
        // No assertion follows on purpose: the failure mode IS the throw escaping this call.
        // `handler is CoroutineExceptionHandler` would be vacuously true from its declared type.
    }

    /**
     * The absence gate. Whether the scope actually CARRIES the handler is invisible from outside:
     * `serviceScope` is private, and a scope built without it looks identical until something throws
     * on a real IO thread and the process is already gone. The declaration is the only place the
     * property is observable, so the source is the instrument.
     */
    @Test
    fun `serviceScope is constructed with that handler, not bare`() {
        val source = File("src/main/kotlin/com/gruning/mimir/rewrite/PerformanceOverlayService.kt")
        assertTrue("source not found at ${source.absolutePath}", source.exists())
        val declaration = source.readText()
            .substringAfter("private val serviceScope =")
            .substringBefore("\n\n")

        assertTrue(
            "serviceScope no longer carries a CoroutineExceptionHandler -- a throw on it will reach " +
                "the thread's uncaught handler and take Mimir down with it. Declaration was: " +
                declaration,
            declaration.contains("FailureHandler") || declaration.contains("CoroutineExceptionHandler"),
        )
    }
}
