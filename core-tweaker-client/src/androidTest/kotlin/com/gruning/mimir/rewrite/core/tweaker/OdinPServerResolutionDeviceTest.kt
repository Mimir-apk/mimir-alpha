package com.gruning.mimir.rewrite.core.tweaker

import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only proof of the vendor Binder lookup available to an ordinary release-signed app. */
@RunWith(AndroidJUnit4::class)
class OdinPServerResolutionDeviceTest {
    @Test
    fun getServiceResolvesTheLiveVendorBinder() {
        assertTrue("This proof is scoped to Odin3", Build.MODEL.equals("Odin3", ignoreCase = true))

        val getService = resolve("getService")
        val checkService = resolve("checkService")
        Log.i(
            ProofTag,
            "getService=${getService.describe()} checkService=${checkService.describe()}",
        )

        assertNotNull(
            "ServiceManager.getService did not expose the running PServerBinder",
            getService.binder,
        )
        assertTrue("Resolved PServerBinder is not alive", getService.binder?.isBinderAlive == true)
    }

    private fun resolve(methodName: String): Resolution = try {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val method = serviceManager.getDeclaredMethod(methodName, String::class.java)
            .apply { isAccessible = true }
        Resolution(
            binder = method.invoke(null, ServiceName) as? IBinder,
            error = null,
        )
    } catch (error: Throwable) {
        Resolution(
            binder = null,
            error = generateSequence(error) { it.cause }.joinToString(" -> ") {
                "${it.javaClass.name}:${it.message}"
            },
        )
    }

    private fun Resolution.describe(): String =
        "binder=${binder != null},alive=${binder?.isBinderAlive},error=$error"

    private data class Resolution(
        val binder: IBinder?,
        val error: String?,
    )

    private companion object {
        const val ServiceName = "PServerBinder"
        const val ProofTag = "MimirPServerResolution"
    }
}
