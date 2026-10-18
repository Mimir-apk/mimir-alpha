package com.gruning.mimir.rewrite.core.companionnative

data class ForegroundMonitorCadence(
    val taskEvidencePollMs: Long = 100L,
    val mirrorPollMs: Long = 250L,
    val noUsageAccessPollMs: Long = 5_000L,
) {
    init {
        require(taskEvidencePollMs > 0L)
        require(mirrorPollMs > 0L)
        require(noUsageAccessPollMs > 0L)
    }
}

enum class ForegroundMonitorDisposition {
    NO_DISPLAY,
    PRESENTING,
    MIRROR_PRESENTED,
    LAUNCH_PENDING,
    YIELDED,
    RECLAIM_PENDING,
    RECOVERY_NEEDED,
}

data class ForegroundMonitorDemand(
    val disposition: ForegroundMonitorDisposition,
    val taskObservationPending: Boolean = false,
    val externalFollowActive: Boolean = true,
    /** Cold persisted recovery has no local task evidence; display attachment only proves availability. */
    val recoveryTaskEvidenceRequired: Boolean = true,
)

class ForegroundMonitorCadencePolicy(
    private val cadence: ForegroundMonitorCadence = ForegroundMonitorCadence(),
) {
    /** Null means the UsageStats loop is stopped until demand changes. */
    fun nextDelayMs(demand: ForegroundMonitorDemand, usageAccessGranted: Boolean): Long? {
        if (demand.disposition == ForegroundMonitorDisposition.NO_DISPLAY) return null
        val taskEvidenceRequired = demand.taskObservationPending || when (demand.disposition) {
            ForegroundMonitorDisposition.LAUNCH_PENDING,
            ForegroundMonitorDisposition.RECLAIM_PENDING,
            -> true
            ForegroundMonitorDisposition.RECOVERY_NEEDED -> demand.recoveryTaskEvidenceRequired
            else -> false
        }
        val externalFollowRequired = demand.externalFollowActive && when (demand.disposition) {
            ForegroundMonitorDisposition.PRESENTING,
            ForegroundMonitorDisposition.MIRROR_PRESENTED,
            ForegroundMonitorDisposition.YIELDED,
            -> true
            else -> false
        }
        if (!taskEvidenceRequired && !externalFollowRequired) return null
        if (!usageAccessGranted) return cadence.noUsageAccessPollMs
        return if (taskEvidenceRequired) cadence.taskEvidencePollMs else cadence.mirrorPollMs
    }

    /**
     * The beat of the launch cover's activity-resume observation ([ActivityResumeObserver]): the task-evidence cadence,
     * 100 ms by default, so a resume is read within one beat of UsageStats publishing it.
     *
     * It is its own answer, and not a [ForegroundMonitorDemand], because [nextDelayMs] returns null for
     * [ForegroundMonitorDisposition.NO_DISPLAY] before it looks at anything else. A single-screen device (the Nova, the RG
     * Rotate, an emulator) has no secondary display, so the shared loop never runs there and a launch that waited on it
     * would hear nothing until its ceiling. Null here means there is nothing to read: without Usage Access the events do
     * not exist, and the cover is never armed without it.
     */
    fun launchCoverDelayMs(usageAccessGranted: Boolean): Long? =
        if (usageAccessGranted) cadence.taskEvidencePollMs else null
}
