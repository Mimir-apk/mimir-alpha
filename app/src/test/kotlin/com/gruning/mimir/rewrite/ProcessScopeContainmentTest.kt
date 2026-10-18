package com.gruning.mimir.rewrite

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.isActive
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Process-scoped coroutine boundaries must turn an uncaught child failure into an error log while
 * keeping the supervisor, its sibling work, and later work alive. The production scopes themselves
 * are intentionally private: their source declarations are pinned below because a scope with no
 * injected seam has no JVM-observable difference until a real child throws on a real dispatcher.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class ProcessScopeContainmentTest {

    @Before
    fun setUp() {
        ShadowLog.clear()
    }

    @Test
    fun `contained child failure leaves sibling and later work runnable`() = runBlocking {
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Unconfined + CompanionProcessRuntime.processScopeFailureHandler,
        )
        val siblingStarted = CompletableDeferred<Unit>()
        val releaseSibling = CompletableDeferred<Unit>()
        val siblingRan = AtomicBoolean(false)
        val laterRan = AtomicBoolean(false)
        try {
            val sibling = scope.launch(CoroutineName("scope-sibling")) {
                siblingStarted.complete(Unit)
                releaseSibling.await()
                siblingRan.set(true)
            }
            siblingStarted.await()
            val failed = scope.launch(CoroutineName("scope-failure")) {
                error("injected process-scope failure")
            }
            failed.join()

            assertTrue("the handler must absorb the failed child at the supervisor boundary", scope.isActive)
            assertTrue(
                "the injected failure must be emitted at the production logging boundary",
                ShadowLog.getLogs().any {
                    it.tag == "MimirCompanionRuntime" &&
                        it.msg.contains("background process work failed") &&
                        it.throwable?.message == "injected process-scope failure"
                },
            )

            releaseSibling.complete(Unit)
            sibling.join()
            assertTrue("a failed child must not cancel its sibling", siblingRan.get())

            val later = scope.launch(CoroutineName("scope-later-work")) {
                laterRan.set(true)
            }
            later.join()
            assertTrue("the same process scope must accept later work", laterRan.get())
        } finally {
            scope.cancel()
        }
    }

    /**
     * Source pins for F2. These are deliberately labelled source pins: the eight production scopes
     * have different owners and construction lifetimes, and no test-only factory should replace them.
     */
    @Test
    fun `all eight named process scopes carry a logging exception boundary`() {
        val declarations = listOf(
            "app/src/main/kotlin/com/gruning/mimir/rewrite/CompanionProcessRuntime.kt" to
                "processScopeFailureHandler",
            "app/src/main/kotlin/com/gruning/mimir/rewrite/CompanionState.kt" to
                "mainScopeFailureHandler",
            "app/src/main/kotlin/com/gruning/mimir/rewrite/FileBrowserStore.kt" to
                "scopeFailureHandler",
            "app/src/main/kotlin/com/gruning/mimir/rewrite/GameIdentityRemapReplayer.kt" to
                "scopeFailureHandler",
            "app/src/main/kotlin/com/gruning/mimir/rewrite/IdentityEnrichmentCoordinator.kt" to
                "scopeFailureHandler",
            "app/src/main/kotlin/com/gruning/mimir/rewrite/StorageVolumeMonitor.kt" to
                "scopeFailureHandler",
            "core-audio/src/main/kotlin/com/gruning/mimir/rewrite/core/audio/MimirFrontendMusic.kt" to
                "scopeFailureHandler",
            "feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/Warmup.kt" to
                "scopeFailureHandler",
        )

        declarations.forEach { (relativePath, handlerName) ->
            val source = sourceFile(relativePath).readText()
            val scopeStart = source.indexOf("CoroutineScope(")
            val scopeDeclaration = scopeStart.takeIf { it >= 0 }?.let {
                source.substring(it, minOf(source.length, it + 260))
            }
            assertTrue(
                "$relativePath must attach $handlerName to its CoroutineScope construction (source pin)",
                scopeDeclaration?.contains(handlerName) == true,
            )
            val handlerStart = source.indexOf("$handlerName = CoroutineExceptionHandler")
            val handlerDeclaration = handlerStart.takeIf { it >= 0 }?.let {
                source.substring(it, minOf(source.length, it + 300))
            }
            assertTrue(
                "$relativePath must log the throwable at the scope boundary (source pin)",
                handlerDeclaration?.let { it.contains("Log.e(") || it.contains("android.util.Log.e(") } == true,
            )
        }
    }

    private fun sourceFile(relativePath: String): File = listOf(
        File(relativePath),
        File("../$relativePath"),
        File("../../$relativePath"),
    ).firstOrNull(File::isFile)
        ?: throw AssertionError("$relativePath not reachable from the test working directory")
}
