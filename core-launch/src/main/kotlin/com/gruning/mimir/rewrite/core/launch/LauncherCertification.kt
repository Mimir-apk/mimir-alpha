package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.PlatformId

enum class LauncherCertificationTruthLabel {
    VISUAL_CERTIFIED,
    ENGINEERING_PASS,
    PHYSICAL_THOR_PASS,
    REAL_SAF_PASS,
    OPEN_EMULATOR_ONLY,
    MANUAL_REQUIRED,
    BLOCKED,
    NOT_CERTIFIED,
    FAIL,
}

/**
 * Explicit proof input for launcher certification. A dispatch-only LaunchProof cannot satisfy this type.
 * Evidence is accepted only when its platform, emulator key, and package match the live resolved target.
 */
data class LauncherCertificationEvidence(
    val platformId: PlatformId,
    val emulatorId: EmulatorId,
    val route: LauncherCertificationRouteIdentity,
    val truthLabel: LauncherCertificationTruthLabel,
    val proofStatus: String,
    val capturedAtMillis: Long,
)

/** The exact dispatch envelope that was observed; stale package-only evidence cannot promote a new route. */
data class LauncherCertificationRouteIdentity(
    val emulatorPackage: String,
    val targetActivity: String,
    val locatorForm: LaunchLocatorForm,
    val retroArchCore: String,
)

data class LauncherCertificationPlatform(
    val platformId: String,
    val truthLabel: LauncherCertificationTruthLabel,
    val certified: Boolean,
    val launchReady: Boolean,
    val testLaunchReady: Boolean,
    val requiresManualCoreProof: Boolean,
    val openOnly: Boolean,
    val gameLaunchReady: Boolean,
    val launchDispatchReady: Boolean,
    val installed: Boolean,
    val emulatorPackage: String,
    val targetActivity: String,
    val proofStatus: String,
    val visualCertification: String,
    val message: String,
)

data class LauncherCertificationByPlatform(
    val schemaVersion: String = SCHEMA_VERSION,
    val source: String = SOURCE,
    val updatedAt: Long,
    val platforms: Map<String, LauncherCertificationPlatform>,
    val truth: String = TRUTH,
) {
    companion object {
        const val SCHEMA_VERSION = "mimirLauncherCertificationByPlatform.v1"
        const val SOURCE = "mimir-launch-proof-engine"
        const val TRUTH =
            "Launcher certification is proof-owned. UI badges read this object; installed/configured state alone never becomes a certified claim."
    }
}

object LauncherCertificationProjector {
    fun project(
        platformIds: Collection<PlatformId>,
        statusFor: (PlatformId) -> PlatformEmulatorStatus,
        evidence: Collection<LauncherCertificationEvidence> = emptyList(),
        updatedAtMillis: Long,
    ): LauncherCertificationByPlatform {
        val platforms = platformIds
            .distinctBy { it.value }
            .associate { platformId ->
                val status = statusFor(platformId)
                platformId.value to platform(platformId, status, evidence)
            }
        return LauncherCertificationByPlatform(
            updatedAt = updatedAtMillis,
            platforms = platforms,
        )
    }

    private fun platform(
        platformId: PlatformId,
        status: PlatformEmulatorStatus,
        evidence: Collection<LauncherCertificationEvidence>,
    ): LauncherCertificationPlatform {
        val emulator = status.resolved
        val dispatchReady = emulator?.let {
            ShippingLaunchProfiles.isCandidateDispatchSupported(platformId, it)
        } == true
        val installed = emulator?.installed == true
        val launchReady = installed && dispatchReady
        val requiresManualCoreProof = emulator?.isRetroArchCore == true
        val openOnly = emulator?.locatorForm == LaunchLocatorForm.NONE
        val liveRoute = emulator?.let {
            LauncherCertificationRouteIdentity(
                emulatorPackage = it.packageName,
                targetActivity = ShippingLaunchProfiles.targetActivityFor(platformId, it).orEmpty(),
                locatorForm = it.locatorForm,
                retroArchCore = it.retroArchCore.orEmpty(),
            )
        }
        val matchingEvidence = emulator?.let { target ->
            evidence
                .asSequence()
                .filter {
                    it.platformId == platformId &&
                        it.emulatorId == target.id &&
                        it.route == liveRoute
                }
                .maxByOrNull { it.capturedAtMillis }
        }
        val truthLabel = matchingEvidence?.truthLabel ?: if (openOnly) {
            LauncherCertificationTruthLabel.OPEN_EMULATOR_ONLY
        } else {
            LauncherCertificationTruthLabel.NOT_CERTIFIED
        }
        val certified = truthLabel == LauncherCertificationTruthLabel.VISUAL_CERTIFIED
        val proofStatus = matchingEvidence?.proofStatus ?: when {
            openOnly -> "OPEN_EMULATOR_ONLY"
            requiresManualCoreProof -> "CORE_PROOF_REQUIRED"
            dispatchReady -> "ENGINEERING_CANDIDATE"
            status.candidates.isNotEmpty() -> "RECOGNIZED_ONLY"
            else -> "UNSUPPORTED"
        }

        return LauncherCertificationPlatform(
            platformId = platformId.value,
            truthLabel = truthLabel,
            certified = certified,
            launchReady = launchReady,
            testLaunchReady = launchReady && requiresManualCoreProof,
            requiresManualCoreProof = requiresManualCoreProof,
            openOnly = openOnly,
            gameLaunchReady = launchReady && !requiresManualCoreProof && !openOnly,
            launchDispatchReady = dispatchReady,
            installed = installed,
            emulatorPackage = emulator?.packageName.orEmpty(),
            targetActivity = emulator?.let {
                ShippingLaunchProfiles.targetActivityFor(platformId, it)
            }.orEmpty(),
            proofStatus = proofStatus,
            visualCertification = truthLabel.name,
            message = when {
                certified -> "Visual launch certified."
                openOnly -> "Emulator shell can open; direct game launch is not certified."
                requiresManualCoreProof ->
                    "RetroArch core route is testable; certification requires exact platform/core proof."
                dispatchReady -> "Launch dispatch configured; visual certification pending."
                else -> "Launch is not certified for this platform yet."
            },
        )
    }
}
