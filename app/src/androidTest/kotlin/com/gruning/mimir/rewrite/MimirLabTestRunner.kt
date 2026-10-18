package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Fail-closed runner for the separate-UID lab target.
 *
 * A broad connectedAndroidTest invocation is deliberately rejected. Every admitted class is named
 * explicitly so live-provider/UI/native fixtures cannot accidentally run on personal hardware.
 */
class MimirLabTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application {
        check(className == MimirLabApplication::class.java.name) {
            "Mimir instrumentation requires the manifest-selected inert lab Application."
        }
        check(context?.packageName == MIMIR_LAB_PACKAGE) {
            "Mimir instrumentation may target only $MIMIR_LAB_PACKAGE."
        }
        return super.newApplication(classLoader, className, context)
    }

    override fun onCreate(arguments: Bundle) {
        MimirLabTestPolicy.requireAllowed(arguments)
        super.onCreate(arguments)
    }
}

internal object MimirLabTestPolicy {
    private val allowedClasses = setOf(
        // Read-only CPU profiling: decodes three bundled audio files to /dev/null and touches no
        // provider, no player data, and no device state. Admitted so the last unprofiled line in
        // core-audio/PROVENANCE.md can be measured on real hardware.
        "com.gruning.mimir.rewrite.AudioDecodeCostDeviceTest",
        "com.gruning.mimir.rewrite.AthenaA1PackagedCatalogDeviceTest",
        "com.gruning.mimir.rewrite.CertifiedVendorOdinDeviceTest",
        // RC-14 identity capture. Read-only: PackageManager queries against two fixed vendor package
        // names and PServerBinder's "Probe*" attest() operations only — never a write/delete/mutate
        // vendor operation, no owner-package data touched. Retained as a refusal diagnostic; the
        // contract row it was built to author no longer exists.
        "com.gruning.mimir.rewrite.CertifiedVendorIdentityProbeDeviceTest",
        // RC-14 SoC-gate certification, including its own fabricated-attestation negative control.
        // Same read-only envelope as the probe above: PackageManager queries plus PServerBinder's
        // "Probe*" attest() operations. The negative control never reaches the transport at all — it
        // corrupts an already-captured attestation value in memory and re-runs the pure admission
        // policy, so no additional device state is touched to prove the gate can still refuse. The
        // end-to-end block drives the real gateway but stays inside the same read-only envelope --
        // posture reads plus readVendorSetting only, never a write, delete, force-stop or recents.
        "com.gruning.mimir.rewrite.CertifiedVendorSocGateDeviceTest",
        "com.gruning.mimir.rewrite.CustomHeroPreviewHydrationDeviceTest",
        // Launch sound : remuxes a clip's AAC track with the platform's own
        // MediaExtractor and MediaMuxer. Every fixture is synthesized in the test's own cache (a sine tone and a few
        // black frames) and the output is played at zero volume. No provider, no credential, no player data.
        "com.gruning.mimir.rewrite.LaunchAudioRemuxDeviceTest",
        "com.gruning.mimir.rewrite.EnrichSavedColdRecoveryDeviceTest",
        // Read-only timing measurement: plays two bundled assets at zero volume and touches no
        // provider, no player data, and no device state. Admitted so the loop-seam claim in
        // core-audio/PROVENANCE.md can be measured on real hardware instead of staying UNKNOWN.
        "com.gruning.mimir.rewrite.MediaPlayerLoopSeekGapDeviceTest",
        "com.gruning.mimir.rewrite.MimirLabIsolationDeviceTest",
        "com.gruning.mimir.rewrite.RecoveryArchiveAndroidDurabilityTest",
        "com.gruning.mimir.rewrite.RecoveryRestoreAndroidLabTest",
        "com.gruning.mimir.rewrite.RecoveryRestoreProcessDeathPrepareAndroidLabTest",
        "com.gruning.mimir.rewrite.RecoveryRestoreProcessDeathRestartAndroidLabTest",
        "com.gruning.mimir.rewrite.RecoveryArchiveMidCopyCrashPrepareDeviceTest",
        "com.gruning.mimir.rewrite.RecoveryArchiveMidCopyRestartReplayDeviceTest",
        "com.gruning.mimir.rewrite.RecoveryArchivePreCloseCrashPrepareDeviceTest",
        "com.gruning.mimir.rewrite.RecoveryArchivePreCloseRestartReplayDeviceTest",
        "com.gruning.mimir.rewrite.RecoveryArchivePreJournalCrashPrepareDeviceTest",
        "com.gruning.mimir.rewrite.RecoveryArchivePreJournalRestartReplayDeviceTest",
        "com.gruning.mimir.rewrite.RecoveryArchivePostStageCrashPrepareDeviceTest",
        "com.gruning.mimir.rewrite.RecoveryArchivePostStageRestartSweepDeviceTest",
    )
    fun requireAllowed(arguments: Bundle) {
        require(arguments.keySet() == setOf("class")) {
            "The owner-neutral lab accepts exactly one instrumentation argument: class."
        }
        val selected = arguments.getString("class").orEmpty().trim()
        require(selected.isNotEmpty()) {
            "The owner-neutral lab requires an explicit class= selector."
        }
        require(',' !in selected && '#' !in selected) {
            "The owner-neutral lab accepts one complete test class, never a list or method selector."
        }
        require(selected in allowedClasses) {
            "Instrumentation class is not admitted by the owner-neutral lab: $selected"
        }
    }
}
