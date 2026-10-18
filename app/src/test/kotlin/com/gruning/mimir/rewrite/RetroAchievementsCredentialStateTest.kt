package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.RaKeyState
import org.junit.Assert.assertEquals
import org.junit.Test

class RetroAchievementsCredentialStateTest {
    @Test
    fun `durable acceptance restores only with usable ciphertext`() {
        assertEquals(
            RaKeyState.ACCEPTED,
            restoredRaKeyState(true, true, RaKeyState.NEVER_CHECKED),
        )
        assertEquals(
            RaKeyState.NEVER_CHECKED,
            restoredRaKeyState(false, true, RaKeyState.NEVER_CHECKED),
        )
        assertEquals(
            RaKeyState.NEVER_CHECKED,
            restoredRaKeyState(true, false, RaKeyState.NEVER_CHECKED),
        )
    }

    @Test
    fun `activity recreation preserves the process-only rejection latch`() {
        assertEquals(
            RaKeyState.REJECTED_THIS_SESSION,
            restoredRaKeyState(true, true, RaKeyState.REJECTED_THIS_SESSION),
        )
        assertEquals(
            "a pre-validation install keeps the same process rejection latch",
            RaKeyState.REJECTED_THIS_SESSION,
            restoredRaKeyState(true, false, RaKeyState.REJECTED_THIS_SESSION),
        )
    }

    @Test
    fun `pre-validation credentials stay eligible until this session observes rejection`() {
        assertEquals(
            RaKeyState.NEVER_CHECKED,
            restoredRaKeyState(true, false, RaKeyState.NEVER_CHECKED),
        )
        assertEquals(true, hasUsableRetroAchievementsCredentialsForSession(true, RaKeyState.NEVER_CHECKED))
        assertEquals(true, hasUsableRetroAchievementsCredentialsForSession(true, RaKeyState.ACCEPTED))
        assertEquals(false,
            hasUsableRetroAchievementsCredentialsForSession(true, RaKeyState.REJECTED_THIS_SESSION))
        assertEquals(false, hasUsableRetroAchievementsCredentialsForSession(false, RaKeyState.NEVER_CHECKED))
    }
}
