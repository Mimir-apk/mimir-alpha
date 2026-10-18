package com.gruning.mimir.rewrite

import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Background Tweaker work must not be able to kill the launcher.
 *
 * `ownerScope` is `CoroutineScope(SupervisorJob() + Dispatchers.IO)`. `SupervisorJob` stops a sibling
 * failure cancelling the scope; it does NOT stop the exception. Without a handler in the context an
 * uncaught throw reaches the thread's uncaught-exception handler, which on Android kills the process —
 * and everything on this scope talks to a daemon that can be absent, busy or mid-restore.
 *
 * The same missing handler is what made the app unit suite fail roughly one run in five with
 * `UncaughtExceptionsBeforeTest` against whichever `runTest` happened to start next.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class TweakerBridgeOwnerScopeFailureTest {

    @Test
    fun `the handler absorbs a background failure instead of letting it escape`() {
        val handler = TweakerBridge.ownerScopeFailureHandler

        // A CoroutineExceptionHandler that rethrows, or that is absent, is how the process dies.
        handler.handleException(
            CoroutineName("tweaker-probe") + handler,
            IllegalStateException("daemon absent, as it is on every unit-test JVM"),
        )
    }

    /**
     * The absence gate. Whether the scope actually CARRIES the handler is invisible to any behavioural
     * test here — `ownerScope` is private, and a scope built without it looks identical from outside
     * until something throws on a real IO thread and the process is already gone. The declaration is
     * the only place the property is observable, so the source is the instrument.
     */
    @Test
    fun `ownerScope is constructed with that handler, not bare`() {
        val source = File("src/main/kotlin/com/gruning/mimir/rewrite/TweakerBridge.kt")
        assertTrue("source not found at ${source.absolutePath}", source.exists())
        val declaration = source.readText()
            .substringAfter("private val ownerScope =")
            .substringBefore("\n\n")

        assertTrue(
            "ownerScope no longer carries ownerScopeFailureHandler — a throw on it will reach the " +
                "thread's uncaught handler and take Mimir down with it. Declaration was: $declaration",
            declaration.contains("ownerScopeFailureHandler"),
        )
        assertTrue(
            "the handler must be an actual CoroutineExceptionHandler in the context",
            TweakerBridge.ownerScopeFailureHandler is CoroutineExceptionHandler,
        )
    }
}
