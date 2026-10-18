package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TweakerFanMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fan transition durations were measured
 * on the Odin 3: **6.3 s** entering Manual, **8.5 s** returning to Auto, with Back and Close both
 * inert for the whole of it.
 *
 * This existed and nothing pinned it — the app suite stayed green when the behaviour was changed,
 * which is the reason this file exists at all.
 */
class UtilitySurfaceDismissTest {

    @Test
    fun `a fan change no longer traps the player in the modal`() {
        assertFalse(
            "8.5 s of an un-dismissable overlay is worse than letting them leave a change that " +
                "completes regardless -- the result still arrives as a Toast",
            utilitySurfaceHoldsAgainstDismiss(profileChange = null, fanControlChange = fanChange()),
        )
    }

    @Test
    fun `a profile change still holds, so Back reaches its cancellation path`() {
        assertTrue(
            utilitySurfaceHoldsAgainstDismiss(profileChange = profileChange(), fanControlChange = null),
        )
        assertTrue(
            "a profile change holds even with a fan change alongside it",
            utilitySurfaceHoldsAgainstDismiss(profileChange = profileChange(), fanControlChange = fanChange()),
        )
    }

    @Test
    fun `an idle surface is always dismissable`() {
        assertFalse(utilitySurfaceHoldsAgainstDismiss(profileChange = null, fanControlChange = null))
    }

    private fun fanChange() = TweakerFanControlChangeState(
        operationId = 1L,
        priorMode = TweakerFanMode.AUTO,
        requestedMode = TweakerFanMode.MANUAL,
        priorCurveBias = 0,
        requestedCurveBias = 0,
    )

    private fun profileChange() = TweakerProfileChangeState(
        operationId = 2L,
        requestedProfileId = "performance",
        priorProfileId = "balanced",
        phase = TweakerProfileChangePhase.APPLYING,
    )
}
