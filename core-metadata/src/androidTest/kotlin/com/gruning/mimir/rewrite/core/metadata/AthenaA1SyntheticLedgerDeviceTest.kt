package com.gruning.mimir.rewrite.core.metadata

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AthenaA1SyntheticLedgerDeviceTest {

    @Test
    fun exactHostMatrixExecutesOnAndroidRuntime() {
        val started = SystemClock.elapsedRealtimeNanos()
        val report = AthenaA1SyntheticMatrix.evaluate()
        val elapsedNanos = SystemClock.elapsedRealtimeNanos() - started

        assertEquals(26, report.scenarioCount)
        assertEquals(0, report.acceptedEqualityFailures)
        assertEquals(0, report.knownWrongDurableIdentities)
        assertEquals(0, report.filenameCarriedWrongIdentities)
        assertEquals(0, report.embeddedHeaderWrongIdentities)

        val payload =
            report.toCanonicalJson().dropLast(1) +
                ",\"elapsedNanos\":$elapsedNanos}"
        Log.i("AthenaA1", "ATHENA_A1_ANDROID_SCORECARD_JSON=$payload")
        println("ATHENA_A1_ANDROID_SCORECARD_JSON=$payload")
    }
}
