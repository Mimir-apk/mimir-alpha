package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.AndroidGameLaunchProofStore
import com.gruning.mimir.rewrite.core.model.AndroidGameLaunchProof
import com.gruning.mimir.rewrite.core.model.AndroidGameLaunchRecency
import com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGameLaunchProofTest {
    @Test
    fun `library native dispatch records the same successful recency truth as Home`() = runBlocking {
        var recorded: AndroidGameLaunchProof? = null
        val store = object : AndroidGameLaunchProofStore {
            override suspend fun record(proof: AndroidGameLaunchProof) {
                recorded = proof
            }

            override fun observeRecentlyPlayed(limit: Int): Flow<List<AndroidGameLaunchRecency>> = emptyFlow()
        }

        val result = recordAndroidGameDispatchRecency(
            store = store,
            packageName = "com.example.library.game",
            timestampMillis = 9876L,
        )

        assertTrue(result.isSuccess)
        assertEquals("com.example.library.game", recorded?.packageName?.value)
        assertEquals(9876L, recorded?.timestampMillis)
        assertTrue(recorded?.outcome is LaunchOutcome.IntentSent)
    }

    @Test
    fun `dispatched native launch creates one successful package proof`() {
        val proof = androidGameLaunchProofFor(
            packageName = "com.example.game",
            attempt = HomeExternalLaunchAttempt.DISPATCHED,
            timestampMillis = 1234L,
        )

        requireNotNull(proof)
        assertEquals("com.example.game", proof.packageName.value)
        assertEquals(1234L, proof.timestampMillis)
        assertTrue(proof.outcome is LaunchOutcome.IntentSent)
    }

    @Test
    fun `native dispatch failure creates a failure proof but arming failure creates none`() {
        val dispatchFailure = androidGameLaunchProofFor(
            packageName = "com.example.game",
            attempt = HomeExternalLaunchAttempt.DISPATCH_FAILED,
            timestampMillis = 2000L,
        )
        val armingFailure = androidGameLaunchProofFor(
            packageName = "com.example.game",
            attempt = HomeExternalLaunchAttempt.ARMING_FAILED,
            timestampMillis = 3000L,
        )

        val outcome = requireNotNull(dispatchFailure).outcome as LaunchOutcome.Failed
        assertEquals(LaunchDispatchFailure.BRIDGE_ERROR, outcome.reason)
        assertNull(armingFailure)
    }

    @Test
    fun `only a failed native dispatch runs one contained Tweaker unwind`() = runBlocking {
        var unwindCount = 0

        val failed = settleFailedNativeGameLaunch(
            attempt = HomeExternalLaunchAttempt.DISPATCH_FAILED,
            dispatcher = Dispatchers.Unconfined,
            timeoutMillis = 1_000L,
        ) {
            unwindCount += 1
        }
        val dispatched = settleFailedNativeGameLaunch(
            attempt = HomeExternalLaunchAttempt.DISPATCHED,
            dispatcher = Dispatchers.Unconfined,
            timeoutMillis = 1_000L,
        ) {
            unwindCount += 1
        }
        val unarmed = settleFailedNativeGameLaunch(
            attempt = HomeExternalLaunchAttempt.ARMING_FAILED,
            dispatcher = Dispatchers.Unconfined,
            timeoutMillis = 1_000L,
        ) {
            unwindCount += 1
        }

        assertTrue(failed is com.gruning.mimir.rewrite.core.launch.PreDispatchOutcome.Completed)
        assertNull(dispatched)
        assertNull(unarmed)
        assertEquals(1, unwindCount)
    }
}
