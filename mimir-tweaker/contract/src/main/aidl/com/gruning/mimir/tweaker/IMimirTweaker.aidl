package com.gruning.mimir.tweaker;

import android.os.Bundle;
import android.os.IBinder;

/** Signature-protected, process-stable control contract consumed by Mimir. */
interface IMimirTweaker {
    int getApiVersion();
    Bundle getCapabilities();
    Bundle getTelemetry();
    Bundle applyProfile(String profileId, String packageName);
    Bundle restore();
    boolean getDryRun();
    boolean setDryRun(boolean enabled);
    Bundle getCalibrationStatus();
    Bundle startCalibrationObservation(String profileId, String packageName, int durationSeconds);
    Bundle stopCalibrationObservation();
    /** Lab flavor only: apply, restore, and verify rollback drills. */
    Bundle runRollbackDrills(String profileId, String packageName, int drillCount);
    /**
     * Retired (TW04-A). Kept for AIDL source compatibility; always fails closed and never
     * mutates or certifies a calibration report from caller-supplied evidence.
     */
    Bundle recordLabSafetyEvidence(
        boolean writesExercised,
        int rollbackAttempts,
        int rollbackSuccesses,
        boolean rebootRestoreVerified
    );

    /**
     * TW04-D generation-bound API. Methods are appended to preserve all legacy
     * transaction codes. The caller owns request identity and elapsed-realtime
     * operation deadlines; the service owns lease duration policy.
     */
    Bundle applyProfileV4(
        String requestId,
        long deadlineElapsedRealtimeMs,
        IBinder clientToken,
        String profileId,
        String packageName
    );
    Bundle cancelRequestV4(
        String requestId,
        long deadlineElapsedRealtimeMs,
        IBinder clientToken,
        long generation,
        String transactionId
    );
    Bundle renewLeaseV4(
        String requestId,
        long deadlineElapsedRealtimeMs,
        IBinder clientToken,
        long generation,
        String transactionId
    );
    Bundle querySessionV4(
        String requestId,
        long deadlineElapsedRealtimeMs,
        IBinder clientToken
    );
    Bundle restoreSessionV4(
        String requestId,
        long deadlineElapsedRealtimeMs,
        IBinder clientToken,
        long generation,
        String transactionId
    );

    /**
     * B01 system-scope selection. Appended for AIDL transaction-code compatibility with
     * API-v4 clients. profileId is quiet/balanced/performance, or stock to turn tuning off.
     */
    Bundle selectSystemProfileV5(
        String requestId,
        long deadlineElapsedRealtimeMs,
        IBinder clientToken,
        String profileId
    );

    /** Read-only typed admission and current truth for advanced API-v6 controls. */
    Bundle getAdvancedCapabilitiesV6();

    /**
     * Exact-owner bounded fan strategy. mode is AUTO or MANUAL; bias is a service-bounded
     * cooler/quieter curve offset. This API never accepts PWM, sysfs paths, or vendor modes.
     */
    Bundle setFanControlV6(
        String callerRequestId,
        long deadlineElapsedRealtimeMs,
        IBinder clientToken,
        String ownerRequestId,
        long generation,
        String transactionId,
        String mode,
        int curveBias
    );

    /**
     * Reversible Android Game Mode FPS control. Methods are appended to preserve every
     * API-v1..v6 transaction code. The client must start the bounded v7 service lifetime
     * before apply; exact Binder death or an explicit exact-owner restore returns Stock.
     */
    Bundle getFpsLimiterCapabilityV7(String packageName);
    Bundle setFpsLimitV7(
        String callerRequestId,
        long deadlineElapsedRealtimeMs,
        IBinder clientToken,
        String packageName,
        int fps
    );
    Bundle queryFpsLimiterV7(String packageName);
    Bundle restoreFpsLimitV7(
        String callerRequestId,
        long deadlineElapsedRealtimeMs,
        IBinder clientToken,
        long generation,
        String transactionId
    );

    /**
     * LAB-only thermal-derate rung verification programme (WAVE2_THERMAL_DERATE_SPEC.md "Rung
     * verification programme"): applies one compiled-in WAVE2 ladder rung's CPU/GPU clamps for
     * profileId, through the ordinary transaction/journal/Guardian apply path -- never the signed
     * derate-rung (applyDerate) path, and never requiring or consulting qualification state.
     * rungIndex is 1-based; out of range or an unknown profileId fails closed with a readable
     * code rather than clamping to nominal or the floor. Fails closed (LAB_ONLY) whenever
     * DIAGNOSTICS_COMPILED is false, i.e. always in production and safe builds.
     */
    Bundle applyThermalDerateRungLab(String profileId, String packageName, int rungIndex);

    /**
     * RETIRED (API v8). This was the B02 privileged display-rate write; Mimir removed display-rate switching on
     * 2026-09-15 and nothing sends it. The declaration stays so every later transaction code keeps its number, but the
     * daemon now answers every call with a refusal (success=false, code=DISPLAY_RATE_RETIRED) and never writes: it
     * does not reach the privileged transport and does not read key or value.
     */
    Bundle setDisplayRateSettingV8(String key, String value);

    /**
     * API v9 — the DISCOVERED tuning axis remembers its stock value here, so a cap outlives the
     * app that set it. Appended after v8 to preserve every legacy transaction code.
     *
     * **This family stores; it never writes hardware.** Policy R2 (2026-09-01) moved the
     * CAPTURE and RESTORE OWNERSHIP into this service and deliberately left the WRITE in Mimir,
     * because the write already survives the app being killed — it is a privileged sysfs write and
     * nothing in Mimir undoes it on death — while the in-process capture does not. What a kill
     * actually destroys is the way back, so that is the only thing that had to move.
     *
     * **No sysfs path crosses this boundary, and none can.** A privileged, signature-protected
     * process taking a node path from an unprivileged one is the escalation shape this contract has
     * refused since v6 ("This API never accepts PWM, sysfs paths, or vendor modes"). Because the
     * write stayed in Mimir, nothing here needs to name a node: the shape is fixed to the two axes
     * the discovered tuner knows, so the API cannot carry a path rather than merely declining to.
     *
     * **Values are still admitted against this service's OWN discovery** — the CPU ids and their
     * OPP tables, and the GPU power-level count, as this process enumerated them — so a record
     * naming a CPU or a frequency the hardware does not publish is refused rather than stored.
     * Never consults the signed qualification manifest, the transaction journal, the Guardian or a
     * lease: it is reachable in every posture and performs no hardware mutation at all. (The v8
     * display-rate write that was once reachable the same way is retired: setDisplayRateSettingV8
     * now always refuses and writes nothing.)
     *
     * Rows are the QoS node's own `cpuId:kHz` spelling, space separated, or "" for absent. GPU
     * levels are -1 when absent.
     */
    Bundle recordDiscoveredSessionV9(
        String bootIdentity,
        String cpuStockRow,
        String cpuRequestedRow,
        int gpuStockMinLevel,
        int gpuStockMaxLevel,
        int gpuRequestedMinLevel,
        int gpuRequestedMaxLevel
    );

    /** What this service is holding for the discovered axis, or held=false. */
    Bundle queryDiscoveredSessionV9();

    /** Forget the discovered-axis session. Called once Mimir has given the device back. */
    Bundle clearDiscoveredSessionV9();

    // -----------------------------------------------------------------------------------------
    // v10 — the discovered clock ceiling, WRITTEN by the daemon (policy 2026-09-03; built
    // 2026-09-11). Keys, outcomes and refusal codes: TweakerDiscoveredCeilingContract. No sysfs
    // path crosses this boundary; the daemon re-admits every value against its own discovery.
    // Appended LAST on purpose: AIDL transaction codes follow declaration order, so every older
    // method keeps its code and an old client or an old daemon stays compatible on the old calls.
    // -----------------------------------------------------------------------------------------

    /** Request a ceiling on one discovered policy. Reply: outcome APPLIED (admittedKhz, inForceKhz) or REFUSED (refusalCode, refusalDetail). */
    Bundle applyDiscoveredCeilingV10(int axis, int policyId, int ceilingKhz);

    /** Give back exactly what the daemon captured, on the CPUs/levels still holding the daemon's value. Reply: WITHDRAWN | NOTHING_HELD | REFUSED. */
    Bundle withdrawDiscoveredCeilingV10();

    /** Is the daemon holding a discovered ceiling for THIS boot? Reply: HELD (+ record fields) | NOT_HELD. */
    Bundle queryDiscoveredCeilingV10();

    // -----------------------------------------------------------------------------------------
    // v11 -- a game Mimir launches cannot take audio focus . Keys,
    // outcomes and refusal codes: TweakerGameAudioFocusContract. The caller names one package and
    // nothing else; the daemon owns the command templates. Appended LAST, as v10 was, so every
    // older method keeps its transaction code.
    // -----------------------------------------------------------------------------------------

    /**
     * Make packageName's requestAudioFocus fail (app-op TAKE_AUDIO_FOCUS=ignore) so other music keeps
     * playing. clientToken is a Binder owned by the caller; its death releases the hold. Reply:
     * outcome HELD only after a readback of ignore, else REFUSED (refusalCode, releaseDebt).
     */
    Bundle holdGameAudioFocusV11(IBinder clientToken, String packageName);

    /** Put the held package back to default and confirm it. Takes no argument on purpose. Reply: RELEASED | NOTHING_HELD | RETAINED. */
    Bundle releaseGameAudioFocusV11();

    /** Is a package held? Reply: HELD (+ packageName) | NOT_HELD. */
    Bundle queryGameAudioFocusV11();
}
