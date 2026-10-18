package com.gruning.mimir.rewrite.core.tweaker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.tweaker.IMimirTweaker
import com.gruning.mimir.tweaker.TweakerServiceActions
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class TweakerFpsLimiterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `v7 start action is explicit and performs no bind`() {
        var started: Intent? = null
        var binds = 0
        val session = TweakerSession(
            appContext = context,
            bindService = { _, _, _ -> binds++; false },
            unbindService = {},
            startForegroundService = { intent ->
                started = intent
                intent.component
            },
        )

        assertTrue(session.beginFpsLimitSessionV7())
        assertEquals(TweakerServiceActions.ACTION_BEGIN_FPS_LIMIT_SESSION_V7, started?.action)
        assertEquals(0, binds)
        assertFalse(session.isBound)
    }

    @Test
    fun `v7 capability apply and exact restore reuse one process owner token`() = runBlocking {
        val packageName = "dev.eden.eden_emulator"
        var applyToken: IBinder? = null
        var restoreToken: IBinder? = null
        val api = proxyApi { method, args ->
            when (method) {
                "getApiVersion" -> 7
                "getFpsLimiterCapabilityV7" -> capabilityBundle(args[0] as String)
                "setFpsLimitV7" -> {
                    val requestId = args[0] as String
                    val deadline = args[1] as Long
                    applyToken = args[2] as IBinder
                    appliedBundle(requestId, deadline, args[3] as String, args[4] as Int)
                }
                "restoreFpsLimitV7" -> {
                    restoreToken = args[2] as IBinder
                    restoredBundle(args[0] as String, args[1] as Long)
                }
                else -> null
            }
        }
        val session = connectedSession(api)

        assertTrue(session.bind())
        val capability = session.getFpsLimiterCapabilityV7(7, packageName)
        assertEquals(listOf(30, 40, 60, 120), capability?.frameLimitsHz)
        val attempt = requireNotNull(session.createFpsApplyAttemptV7(packageName, 60, 5_000L))
        val applied = session.setFpsLimitV7(7, attempt) as TweakerFpsApplyAssessment.Applied
        assertEquals(60, applied.owner.targetFps)
        assertEquals(
            TweakerFpsRestoreAssessment.Restored,
            session.restoreFpsLimitV7(7, applied.owner, 2_500L),
        )
        assertTrue(applyToken === restoreToken)
    }

    @Test
    fun `strict parser treats malformed active truth as uncertain`() {
        val malformed = Bundle().apply {
            putInt("apiVersion", 7)
            putBoolean("success", true)
            putString("requestedPackageName", "dev.eden.eden_emulator")
            putString("state", "ACTIVE")
            putBoolean("mutationMayBeActive", true)
            putString("ownerRequestId", "mimir.fps.request")
            putLong("generation", 1L)
            putString("transactionId", "mimir.fps.tx")
            putString("packageName", "dev.eden.eden_emulator")
            putInt("targetFps", 59)
        }

        assertTrue(
            parseTweakerFpsQueryBundle(malformed, 7, "dev.eden.eden_emulator") is
                TweakerFpsQueryAssessment.Uncertain,
        )
    }

    @Test
    fun `strict parser rejects active owner for another package`() {
        val mismatched = Bundle().apply {
            putInt("apiVersion", 7)
            putBoolean("success", true)
            putString("requestedPackageName", "dev.eden.eden_emulator")
            putString("state", "ACTIVE")
            putBoolean("mutationMayBeActive", true)
            putString("ownerRequestId", "mimir.fps.request")
            putLong("generation", 1L)
            putString("transactionId", "mimir.fps.tx")
            putString("packageName", "org.example.other")
            putInt("targetFps", 60)
        }

        assertTrue(
            parseTweakerFpsQueryBundle(mismatched, 7, "dev.eden.eden_emulator") is
                TweakerFpsQueryAssessment.Uncertain,
        )
    }

    private fun connectedSession(api: IMimirTweaker) = TweakerSession(
        appContext = context,
        bindService = { _, connection, _ ->
            connection.onServiceConnected(COMPONENT, Binder())
            true
        },
        unbindService = {},
        asInterface = { api },
        elapsedRealtimeMs = { 1_000L },
    )

    private fun proxyApi(dispatch: (String, Array<out Any?>) -> Any?): IMimirTweaker {
        val binder = Binder()
        return Proxy.newProxyInstance(
            IMimirTweaker::class.java.classLoader,
            arrayOf(IMimirTweaker::class.java),
        ) { _, method, nullableArgs ->
            val args = nullableArgs.orEmpty()
            when (method.name) {
                "asBinder" -> binder
                else -> dispatch(method.name, args) ?: when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    Bundle::class.java -> Bundle()
                    else -> null
                }
            }
        } as IMimirTweaker
    }

    private fun capabilityBundle(packageName: String) = Bundle().apply {
        putInt("apiVersion", 7)
        putBoolean("success", true)
        putString("code", "FPS_LIMIT_AVAILABLE")
        putString("message", "available")
        putString("packageName", packageName)
        putIntegerArrayList("frameLimitsHz", arrayListOf(30, 40, 60, 120))
    }

    private fun appliedBundle(requestId: String, deadline: Long, packageName: String, fps: Int) =
        Bundle().apply {
            putInt("apiVersion", 7)
            putString("callerRequestId", requestId)
            putLong("callerDeadlineElapsedRealtimeMs", deadline)
            putBoolean("success", true)
            putString("code", "FPS_LIMIT_APPLIED")
            putString("message", "applied")
            putBoolean("mutationMayBeActive", true)
            putBoolean("restoreDebtRetained", true)
            putString("ownerRequestId", requestId)
            putLong("generation", 3L)
            putString("transactionId", "mimir.fps.tx.3")
            putString("packageName", packageName)
            putInt("targetFps", fps)
        }

    private fun restoredBundle(requestId: String, deadline: Long) = Bundle().apply {
        putInt("apiVersion", 7)
        putString("callerRequestId", requestId)
        putLong("callerDeadlineElapsedRealtimeMs", deadline)
        putBoolean("success", true)
        putString("code", "FPS_LIMIT_RESTORED")
        putString("message", "restored")
        putBoolean("mutationMayBeActive", false)
        putBoolean("restoreDebtRetained", false)
    }

    private companion object {
        val COMPONENT = ComponentName(TweakerSession.TWEAKER_PACKAGE, TweakerSession.TWEAKER_SERVICE)
    }
}
