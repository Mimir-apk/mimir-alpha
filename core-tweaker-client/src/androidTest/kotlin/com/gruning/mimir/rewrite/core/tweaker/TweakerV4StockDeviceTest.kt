package com.gruning.mimir.rewrite.core.tweaker

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * No-write RP5 contract: the installed service must expose authoritative API-v4 Stock truth, the
 * product client must hide all tunable profiles, and the request-scoped binding must be released.
 */
@RunWith(AndroidJUnit4::class)
class TweakerV4StockDeviceTest {
    @Test
    fun emptyActiveStartExpiresAndReturnsToAuthoritativeStock() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = TweakerSession(context)

        assertTrue("signature-protected foreground start was rejected", session.beginActiveSessionV4())
        SystemClock.sleep(12_000L)

        assertTrue("Tweaker did not accept a fresh post-expiry query", session.bind())
        try {
            val apiVersion = requireNotNull(session.getApiVersion())
            assertTrue(apiVersion >= TWEAKER_V4_MIN_API_VERSION)
            assertTrue(
                "expired admission did not settle back to exact Stock",
                session.querySessionV4(apiVersion) is TweakerV4SessionAssessment.Stock,
            )
        } finally {
            session.unbind()
        }

        assertFalse(session.isBound)
    }

    @Test
    fun installedServiceReportsAuthoritativeStockAndReleasesBinding() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = TweakerSession(context)

        assertTrue("signature-protected Tweaker service did not bind", session.bind())
        try {
            val apiVersion = requireNotNull(session.getApiVersion()) {
                "installed Tweaker did not expose an API version"
            }
            assertTrue(apiVersion >= TWEAKER_V4_MIN_API_VERSION)
            assertTrue(
                "installed Tweaker did not report exact mutation-free Stock",
                session.querySessionV4(apiVersion) is TweakerV4SessionAssessment.Stock,
            )

            val capabilities = requireNotNull(session.getCapabilities()) {
                "installed Tweaker did not expose capabilities"
            }
            assertEquals(listOf("stock"), selectableTweakerProfileIds(capabilities))
        } finally {
            session.unbind()
        }

        assertFalse(session.isBound)
    }
}
