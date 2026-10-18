package com.gruning.mimir.tweaker;

/** Shared explicit service actions. This does not change the AIDL transaction surface. */
public final class TweakerServiceActions {
    public static final String ACTION_BEGIN_ACTIVE_SESSION_V4 =
            "com.gruning.mimir.tweaker.action.BEGIN_ACTIVE_SESSION_V4";
    public static final String ACTION_BEGIN_FPS_LIMIT_SESSION_V7 =
            "com.gruning.mimir.tweaker.action.BEGIN_FPS_LIMIT_SESSION_V7";

    /** Debug/LAB owner-validation hold. Production and SAFE services reject this action. */
    public static final String ACTION_BEGIN_LAB_PROFILE_HOLD =
            "com.gruning.mimir.tweaker.action.BEGIN_LAB_PROFILE_HOLD";
    public static final String EXTRA_LAB_APPLY_HOLD = "labApplyHold";
    public static final String EXTRA_PROFILE_ID = "calibrationProfileId";
    public static final String EXTRA_WORKLOAD_PACKAGE = "calibrationWorkloadPackage";
    public static final String EXTRA_LAB_OBSERVE_SECONDS = "labObserveSeconds";
    /**
     * Exact Mimir debug-owner path. It keeps the Guardian-backed hold but does not turn a
     * normal plugged-in game launch into a discharge-only calibration observation.
     */
    public static final String EXTRA_LAB_OWNER_VALIDATION = "labOwnerValidation";

    private TweakerServiceActions() {}
}
