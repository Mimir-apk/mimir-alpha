package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TweakerFanMode
import com.gruning.mimir.rewrite.core.tweaker.TweakerManualFanCapability
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *
 *
 * The fallback is real and stays: killing the frontend fires `OWNER_DEATH_RESTORE` and the fan goes
 * back to vendor mode 4 — observed on the Odin at 06:13:26 when an install force-stopped the app.
 * That is the abandoned-fan guarantee and this ruling does not weaken it. Manual stops the vendor's
 * regulation loop, so nothing may hold the fan while nothing is watching. What persists is the
 * CHOICE -- as of the fan ownership spec (I6, 2026-09-07) the daemon is what re-asserts it once it is
 * watching again; the frontend only evaluates [shouldRestoreRememberedManualFan] below to observe and
 * latch the session, and no longer acts on a positive answer itself
 * (see `AppBrightnessCoordinator.restoreRememberedManualFanIfNeeded`).
 */
class TweakerManualFanRearmTest {

    @Test
    fun `a remembered Manual is restored after the owner-death fallback`() {
        assertTrue(
            shouldRestoreRememberedManualFan(
                capability = capability(TweakerFanMode.AUTO),
                rememberedMode = TweakerFanMode.MANUAL,
                lastAttemptedSessionKey = null,
            ),
        )
    }

    @Test
    fun `a deliberate Auto is never overridden`() {
        assertFalse(
            "picking Auto has to stick as firmly as picking Manual, or the re-arm fights the player",
            shouldRestoreRememberedManualFan(
                capability = capability(TweakerFanMode.AUTO),
                rememberedMode = TweakerFanMode.AUTO,
                lastAttemptedSessionKey = null,
            ),
        )
        assertFalse(
            "and a profile never chosen on is left alone",
            shouldRestoreRememberedManualFan(
                capability = capability(TweakerFanMode.AUTO),
                rememberedMode = null,
                lastAttemptedSessionKey = null,
            ),
        )
    }

    /**
     * **This test asserted the exact opposite until 2026-08-26, and the device overturned it.**
     *
     * It used to read "a session already in Manual is not re-issued a selection it is in", which is
     * only sound if `currentMode` describes the hardware. It does not. On the Odin at
     * 12:31:14 the capability advertised `currentMode=MANUAL` while the fan sat on vendor Auto
     * (`fan_mode=4`, daemon `contractFan=4`): the daemon publishes `fanControlMode` from a DURABLE
     * per-profile preference that outlives session teardown, while the hardware contract comes back
     * at vendor SMART. The old gate read the stale selection, concluded there was nothing to
     * repair, and left the fan on the vendor curve for as long as the device stayed up.
     *
     * So a MANUAL reading is not evidence of Manual, and the re-arm must proceed on it. The cost is
     * one idempotent re-apply per session identity when Manual really is live -- against an
     * already-duty-owned contract `requiresTransition` is false, so no vendor write occurs.
     */
    @Test
    fun `an advertised Manual does not prove the hardware is in Manual, so the re-arm still runs`() {
        assertTrue(
            "currentMode is the daemon's durable SELECTION, not live hardware ownership",
            shouldRestoreRememberedManualFan(
                capability = capability(TweakerFanMode.MANUAL),
                rememberedMode = TweakerFanMode.MANUAL,
                lastAttemptedSessionKey = null,
            ),
        )
    }

    /**
     * The latch is the only thing standing between an untrusted `currentMode` and a re-apply on
     * every projection. With the mode conjunct retired it carries the whole load, so pin it against
     * the advertised-Manual case too, not just the Auto one.
     */
    @Test
    fun `dropping the mode gate does not cost one apply per projection`() {
        val fan = capability(TweakerFanMode.MANUAL)
        assertFalse(
            shouldRestoreRememberedManualFan(
                capability = fan,
                rememberedMode = TweakerFanMode.MANUAL,
                lastAttemptedSessionKey = tweakerFanSessionKey(fan),
            ),
        )
    }

    /**
     * The reboot case the ruling is actually about, in the order the device produced it: the
     * frontend resumes BEFORE the daemon session exists, so the first look has no capability at all
     * and must decline without burning the latch -- otherwise the session that arrives 3.6s later
     * is never acted on and the whole first game runs on vendor Auto.
     */
    @Test
    fun `a look taken before the session exists declines without spending the attempt`() {
        assertFalse(
            "nothing to act on yet",
            shouldRestoreRememberedManualFan(null, TweakerFanMode.MANUAL, null),
        )
        val arrived = capability(TweakerFanMode.AUTO)
        assertTrue(
            "and the session that arrives afterwards still earns its attempt",
            shouldRestoreRememberedManualFan(
                capability = arrived,
                rememberedMode = TweakerFanMode.MANUAL,
                lastAttemptedSessionKey = null,
            ),
        )
    }

    @Test
    fun `one attempt per session, so a refusal can never become a retry loop`() {
        val fan = capability(TweakerFanMode.AUTO)

        assertFalse(
            "the row refreshes about once a second while observed; retrying every tick would " +
                "fight the daemon rather than restore a preference",
            shouldRestoreRememberedManualFan(
                capability = fan,
                rememberedMode = TweakerFanMode.MANUAL,
                lastAttemptedSessionKey = tweakerFanSessionKey(fan),
            ),
        )
        assertTrue(
            "but a genuinely new session earns a fresh attempt",
            shouldRestoreRememberedManualFan(
                capability = fan.copy(generation = fan.generation + 1),
                rememberedMode = TweakerFanMode.MANUAL,
                lastAttemptedSessionKey = tweakerFanSessionKey(fan),
            ),
        )
    }

    @Test
    fun `a daemon that does not offer Manual is never asked for it`() {
        assertFalse(
            shouldRestoreRememberedManualFan(
                capability = capability(TweakerFanMode.AUTO).copy(modes = setOf(TweakerFanMode.AUTO)),
                rememberedMode = TweakerFanMode.MANUAL,
                lastAttemptedSessionKey = null,
            ),
        )
        assertFalse(shouldRestoreRememberedManualFan(null, TweakerFanMode.MANUAL, null))
    }

    // ---- the frontend stops re-arming MANUAL by itself -------------
    //
    // The daemon now owns renewal end-to-end (entering MANUAL from Stock on the ordinary re-arm
    // select, renewing a SMART contract, and re-entering after an external change). No harness in this
    // module can drive `AppBrightnessCoordinator.restoreRememberedManualFanIfNeeded` behaviourally:
    // `TweakerBridge.advancedCapabilities` (the source of the `manualFan` capability it reads) has a
    // `private set`, and the fan-control call it used to make goes straight to
    // `TweakerBridge.setFanControl` -- neither is reachable through the injectable
    // `TweakerProfilePort` fake seam this file's sibling coordinator tests use. So this is a
    // source-text pin on the removed call plus a pin on the small pure observation message that
    // replaced it.

    /**
     * RED FIRST. Before this fix the function's body ended by calling
     * `applyTweakerFanControl(fan, TweakerFanMode.MANUAL, ..., silent = true)` -- a second renewal
     * driver competing with the daemon's own. Every daemon-side rollback of
     * that silent write mints a new generation, a new generation mints a new session key
     * ([tweakerFanSessionKey]), and the new session key re-armed the latch and re-fired the same
     * silent request on the very next projection. The fix keeps the gate evaluation (so the session
     * latch still advances) but the body must never again reach the daemon's own actuation entry
     * point.
     */
    @Test
    fun `restoreRememberedManualFanIfNeeded no longer issues a fan-control request`() {
        val body = appBrightnessCoordinatorSource()
            .substringAfter("private fun restoreRememberedManualFanIfNeeded(capability: TweakerManualFanCapability?) {")
            .substringBefore("\n    }\n")

        assertFalse(
            "the daemon owns renewal (I6); this function must only observe, never actuate",
            body.contains("applyTweakerFanControl("),
        )
    }

    /**
     * The one-line, per-session-key observation that replaced the silent re-apply. Pinned as a pure
     * function since no harness can observe `logFanAssessment` calls
     * through the coordinator.
     */
    @Test
    fun `the observation message names the profile and hands renewal to the daemon`() {
        assertEquals(
            "remembered MANUAL for performance left to the daemon's renewal",
            rememberedManualObservation("performance"),
        )
        assertEquals(
            "remembered MANUAL for balanced left to the daemon's renewal",
            rememberedManualObservation("balanced"),
        )
    }

    private fun appBrightnessCoordinatorSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/AppBrightnessCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/AppBrightnessCoordinator.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("AppBrightnessCoordinator.kt not found from ${File(".").absolutePath}")
    }

    private fun capability(
        mode: TweakerFanMode,
        dutyOwned: Boolean? = null,
    ) = TweakerManualFanCapability(
        modes = setOf(TweakerFanMode.AUTO, TweakerFanMode.MANUAL),
        curveBiasRange = -100..100,
        curveBiasStep = 10,
        curveSensorType = "GPU",
        currentMode = mode,
        currentCurveBias = 0,
        ownerRequestId = "mimir.system.fan",
        generation = 7,
        transactionId = "tx-1",
        profileId = "performance",
        hardwareDutyOwned = dutyOwned,
    )

    /**
     * **The three cases, after the daemon learned to attest ownership (2026-08-26 afternoon).**
     *
     * The conjunct above changed twice in one day and the middle state is easy to re-introduce by
     * accident, so all three live in one test where they can be read together.
     */
    @Test
    fun `currentMode is trusted only where the daemon attests hardware ownership`() {
        assertTrue(
            "dutyOwned=null -- an older Tweaker that cannot attest. currentMode may be a stale " +
                "selection, so it is ignored and the re-arm proceeds.",
            shouldRestoreRememberedManualFan(
                capability(TweakerFanMode.MANUAL, dutyOwned = null),
                TweakerFanMode.MANUAL,
                null,
            ),
        )
        assertFalse(
            "dutyOwned=true with MANUAL -- genuinely in Manual. Skip. This is the property the " +
                "ORIGINAL conjunct wanted and had no way to check.",
            shouldRestoreRememberedManualFan(
                capability(TweakerFanMode.MANUAL, dutyOwned = true),
                TweakerFanMode.MANUAL,
                null,
            ),
        )
        assertTrue(
            "dutyOwned=false -- the parser has already rewritten currentMode to AUTO, so this is " +
                "what a repair looks like: the defect being fixed.",
            shouldRestoreRememberedManualFan(
                capability(TweakerFanMode.AUTO, dutyOwned = false),
                TweakerFanMode.MANUAL,
                null,
            ),
        )
    }

    /**
     * The attestation must never be allowed to override a deliberate Auto -- it answers "is the
     * hardware Mimir's", never "what did the player choose".
     */
    @Test
    fun `an attested hardware Auto still does not override a deliberate Auto`() {
        assertFalse(
            shouldRestoreRememberedManualFan(
                capability(TweakerFanMode.AUTO, dutyOwned = false),
                TweakerFanMode.AUTO,
                null,
            ),
        )
    }
}
