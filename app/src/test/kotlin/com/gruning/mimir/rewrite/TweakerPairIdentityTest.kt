package com.gruning.mimir.rewrite

import android.content.pm.PackageManager
import com.gruning.mimir.rewrite.core.tweaker.TweakerDiscoveredCeiling
import com.gruning.mimir.rewrite.core.tweaker.TweakerFanControlReadout
import com.gruning.mimir.rewrite.core.tweaker.TweakerFanControlStrategy
import com.gruning.mimir.rewrite.core.tweaker.TweakerSession
import com.gruning.mimir.rewrite.core.tweaker.TweakerTelemetry
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityAvailability
import com.gruning.mimir.rewrite.core.tweaker.tweakerUtilityAvailability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TweakerPairIdentityTest {
  @Test
  fun `utility fan truth is fresh active and profile exact`() {
    val readout = TweakerFanControlReadout(
      profileId = "balanced",
      strategy = TweakerFanControlStrategy.HOLD_TARGET,
      targetTempC = 65,
      calibrationPowerReferenceMw = 11_250,
      dutyPercent = 25,
      rpm = 0,
    )
    val telemetry = TweakerTelemetry(
      sampledAtElapsedRealtimeMs = 10_000,
      foregroundPackageName = null,
      frameStatus = null,
      frameAverageFpsMilli = null,
      cpuTempC = null,
      gpuTempC = null,
      cpuBusyPercent = null,
      gpuBusyPercent = null,
      batteryPowerMw = null,
      batteryStatus = null,
      fanControl = readout,
    )

    assertEquals(readout, freshUtilityFanControl(telemetry, "balanced", 13_500))
    assertNull(freshUtilityFanControl(telemetry, "balanced", 13_501))
    assertNull(freshUtilityFanControl(telemetry, "balanced", 9_999))
    assertNull(freshUtilityFanControl(telemetry, "quiet", 10_000))
    assertNull(freshUtilityFanControl(telemetry, TweakerBridge.STOCK_PROFILE, 10_000))
    assertNull(
      freshUtilityFanControl(
        telemetry.copy(sampledAtElapsedRealtimeMs = null),
        "balanced",
        10_000,
      ),
    )
  }

  @Test
  fun `utility fan gpu temperature is fresh within the same window as the fan readout`() {
    val telemetry = TweakerTelemetry(
      sampledAtElapsedRealtimeMs = 10_000,
      foregroundPackageName = null,
      frameStatus = null,
      frameAverageFpsMilli = null,
      cpuTempC = null,
      gpuTempC = 62,
      cpuBusyPercent = null,
      gpuBusyPercent = null,
      batteryPowerMw = null,
      batteryStatus = null,
      fanControl = null,
    )

    // Same freshness window as freshUtilityFanControl (UTILITY_FAN_TELEMETRY_MAX_AGE_MS = 3_500).
    assertEquals(62, freshUtilityFanGpuTempC(telemetry, 13_500))
    assertNull(freshUtilityFanGpuTempC(telemetry, 13_501))
    assertNull(freshUtilityFanGpuTempC(telemetry, 9_999))
    // Unlike the fan readout, this is not gated on a selected profile -- only freshness.
    assertNull(
      freshUtilityFanGpuTempC(telemetry.copy(sampledAtElapsedRealtimeMs = null), 10_000),
    )
    assertNull(freshUtilityFanGpuTempC(null, 10_000))
    // A fresh sample with no GPU reading passes the temperature straight through as null.
    assertNull(freshUtilityFanGpuTempC(telemetry.copy(gpuTempC = null), 10_000))
  }

  @Test
  fun `utility fan cpu temperature is fresh within the same window as the fan readout`() {
    val telemetry = TweakerTelemetry(
      sampledAtElapsedRealtimeMs = 10_000,
      foregroundPackageName = null,
      frameStatus = null,
      frameAverageFpsMilli = null,
      // Deliberately different from cpuPackageTempC -- proves this reads hottest-core, not the mean.
      cpuTempC = 89,
      gpuTempC = null,
      cpuBusyPercent = null,
      gpuBusyPercent = null,
      batteryPowerMw = null,
      batteryStatus = null,
      cpuPackageTempC = 61,
      fanControl = null,
    )

    // Same freshness window as freshUtilityFanControl / freshUtilityFanGpuTempC
    // (UTILITY_FAN_TELEMETRY_MAX_AGE_MS = 3_500).
    assertEquals(89, freshUtilityFanCpuTempC(telemetry, 13_500))
    assertNull(freshUtilityFanCpuTempC(telemetry, 13_501))
    assertNull(freshUtilityFanCpuTempC(telemetry, 9_999))
    // Unlike the fan readout, this is not gated on a selected profile -- only freshness.
    assertNull(
      freshUtilityFanCpuTempC(telemetry.copy(sampledAtElapsedRealtimeMs = null), 10_000),
    )
    assertNull(freshUtilityFanCpuTempC(null, 10_000))
    // A fresh sample with no CPU reading passes the temperature straight through as null.
    assertNull(freshUtilityFanCpuTempC(telemetry.copy(cpuTempC = null), 10_000))
    // Hottest core, not the package mean: a fresh sample with cpuTempC null but cpuPackageTempC
    // present must still render null -- this field never falls back to the mean.
    assertNull(
      freshUtilityFanCpuTempC(telemetry.copy(cpuTempC = null, cpuPackageTempC = 61), 10_000),
    )
  }

  @Test
  fun `unchecked and pm error installed truth is null and may not bind`() {
    listOf(TweakerPairState.UNCHECKED, TweakerPairState.PACKAGE_MANAGER_ERROR).forEach { state ->
      val identity = TweakerPairIdentity(state)
      assertNull(identity.installedTruth)
      assertFalse(identity.mayBind)
      assertEquals(
        TweakerUtilityAvailability.UNAVAILABLE,
        tweakerUtilityAvailability(identity.installedTruth, capabilities = null),
      )
    }
  }

  @Test
  fun `absent is the only false installed truth`() {
    val identity = TweakerPairIdentity(TweakerPairState.ABSENT)
    assertEquals(false, identity.installedTruth)
    assertFalse(identity.mayBind)
  }

  @Test
  fun `only eligible may bind`() {
    TweakerPairState.entries.forEach { state ->
      val identity = TweakerPairIdentity(state)
      assertEquals(state == TweakerPairState.ELIGIBLE, identity.mayBind)
    }
  }

  @Test
  fun `non absent inspected states report installed true`() {
    val installedStates = TweakerPairState.entries.filter {
      it !in setOf(
        TweakerPairState.UNCHECKED,
        TweakerPairState.PACKAGE_MANAGER_ERROR,
        TweakerPairState.ABSENT,
      )
    }
    installedStates.forEach { state ->
      assertEquals(true, TweakerPairIdentity(state).installedTruth)
    }
  }

  @Test
  fun `name not found only means absent`() {
    val lookup = inspectTweakerPackageLookup<Any>(
      readPackage = { throw PackageManager.NameNotFoundException("missing") },
      inspectPresent = { error("must not inspect an absent package") },
    )
    assertEquals(TweakerPackageLookupResult.Absent, lookup)
  }

  @Test
  fun `non name not found package lookup errors are pm error`() {
    val lookup = inspectTweakerPackageLookup<Any>(
      readPackage = { throw SecurityException("package visibility uncertain") },
      inspectPresent = { error("must not inspect an uncertain package") },
    )
    assertEquals(TweakerPackageLookupResult.PackageManagerError, lookup)
  }

  @Test
  fun `name not found after package lookup is pm error not absent`() {
    val lookup = inspectTweakerPackageLookup(
      readPackage = { Any() },
      inspectPresent = { throw PackageManager.NameNotFoundException("component raced") },
    )
    assertEquals(TweakerPackageLookupResult.PackageManagerError, lookup)
  }

  @Test
  fun `signature unknown package is pm error`() {
  val identity = classifyTweakerPair(eligiblePresentFacts(signatureResult = PackageManager.SIGNATURE_UNKNOWN_PACKAGE))
    assertEquals(TweakerPairState.PACKAGE_MANAGER_ERROR, identity.state)
  }

  @Test
  fun `signature mismatch blocks bind`() {
    val identity = classifyTweakerPair(eligiblePresentFacts(signatureResult = PackageManager.SIGNATURE_NO_MATCH))
    assertEquals(TweakerPairState.SIGNATURE_MISMATCH, identity.state)
    assertFalse(identity.mayBind)
  }

  @Test
  fun `missing exact service blocks bind`() {
    val identity = classifyTweakerPair(
      eligiblePresentFacts(service = null),
    )
    assertEquals(TweakerPairState.SERVICE_MISSING, identity.state)
  }

  @Test
  fun `non exported service is incompatible`() {
    val identity = classifyTweakerPair(
      eligiblePresentFacts(
        service = TweakerServiceContractFacts(exported = false, permission = TweakerSession.CONTROL_PERMISSION),
      ),
    )
    assertEquals(TweakerPairState.SERVICE_INCOMPATIBLE, identity.state)
  }

  @Test
  fun `wrong control permission on service is incompatible`() {
    val identity = classifyTweakerPair(
      eligiblePresentFacts(
        service = TweakerServiceContractFacts(exported = true, permission = "other.permission"),
      ),
    )
    assertEquals(TweakerPairState.SERVICE_INCOMPATIBLE, identity.state)
  }

  @Test
  fun `disabled component blocks bind`() {
    val identity = classifyTweakerPair(
      eligiblePresentFacts(componentEffectivelyEnabled = false),
    )
    assertEquals(TweakerPairState.COMPONENT_DISABLED, identity.state)
  }

  @Test
  fun `denied control permission blocks bind`() {
    val identity = classifyTweakerPair(
      eligiblePresentFacts(controlPermissionGranted = false),
    )
    assertEquals(TweakerPairState.CONTROL_PERMISSION_DENIED, identity.state)
  }

  @Test
  fun `control permission lookup uncertainty is pm error`() {
    val identity = classifyTweakerPair(
      eligiblePresentFacts(controlPermissionGranted = null),
    )
    assertEquals(TweakerPairState.PACKAGE_MANAGER_ERROR, identity.state)
  }

  @Test
  fun `exact eligible contract may bind`() {
    val identity = classifyTweakerPair(eligiblePresentFacts())
    assertEquals(TweakerPairState.ELIGIBLE, identity.state)
    assertTrue(identity.mayBind)
    assertEquals(true, identity.installedTruth)
  }

  @Test
  fun `component override disabled fails closed`() {
    assertFalse(
      isTweakerServiceComponentEffectivelyEnabled(
        componentEnabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        applicationEnabled = true,
        manifestComponentEnabled = true,
      ),
    )
  }

  @Test
  fun `component default follows manifest enabled flag`() {
    assertTrue(
      isTweakerServiceComponentEffectivelyEnabled(
        componentEnabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
        applicationEnabled = true,
        manifestComponentEnabled = true,
      ),
    )
    assertFalse(
      isTweakerServiceComponentEffectivelyEnabled(
        componentEnabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
        applicationEnabled = true,
        manifestComponentEnabled = false,
      ),
    )
  }

  @Test
  fun `explicit component enable cannot override disabled application`() {
    assertFalse(
      isTweakerServiceComponentEffectivelyEnabled(
        componentEnabledSetting = PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        applicationEnabled = false,
        manifestComponentEnabled = true,
      ),
    )
  }

  @Test
  fun `non eligible states perform zero bind calls`() {
    TweakerPairState.entries.filter { it != TweakerPairState.ELIGIBLE }.forEach { state ->
      var bindCalls = 0
      val connected = runBlocking {
        executeTweakerBindAttempt(TweakerPairIdentity(state)) {
          bindCalls++
          true
        }
      }
      assertEquals("state=$state", 0, bindCalls)
      assertFalse(connected)
    }
  }

  @Test
  fun `eligible performs exactly one bind call`() {
    var bindCalls = 0
    val connected = runBlocking {
      executeTweakerBindAttempt(TweakerPairIdentity(TweakerPairState.ELIGIBLE)) {
        bindCalls++
        true
      }
    }
    assertEquals(1, bindCalls)
    assertTrue(connected)
  }

  @Test
  fun `eligible with null capabilities remains unavailable in ui projection`() {
    val identity = TweakerPairIdentity(TweakerPairState.ELIGIBLE)
    assertEquals(
      TweakerUtilityAvailability.UNAVAILABLE,
      tweakerUtilityAvailability(identity.installedTruth, capabilities = null),
    )
    assertEquals(
      "Mimir Tweaker didn\u2019t respond. Reopen it, then retry.",
      tweakerProfileSupportingText(
        TweakerUtilityAvailability.UNAVAILABLE,
        pairState = TweakerPairState.ELIGIBLE,
      ),
    )
  }

  @Test
  fun `pair failure copy distinguishes mismatch access and package uncertainty`() {
    assertEquals(
      "Install the matching Mimir Tweaker release.",
      tweakerProfileSupportingText(
        TweakerUtilityAvailability.UNAVAILABLE,
        pairState = TweakerPairState.SIGNATURE_MISMATCH,
      ),
    )
    assertEquals(
      "Enable Mimir Tweaker access, then retry.",
      tweakerProfileSupportingText(
        TweakerUtilityAvailability.UNAVAILABLE,
        pairState = TweakerPairState.CONTROL_PERMISSION_DENIED,
      ),
    )
    assertEquals(
      "Mimir couldn’t check Tweaker. Retry.",
      tweakerProfileSupportingText(
        TweakerUtilityAvailability.UNAVAILABLE,
        pairState = TweakerPairState.PACKAGE_MANAGER_ERROR,
      ),
    )
  }

  @Test
  fun `stock only copy identifies a non matching production companion`() {
    assertEquals(
      "This Tweaker build supports Stock only. Install the matching production companion for profiles.",
      tweakerProfileSupportingText(TweakerUtilityAvailability.RESPONDING_STOCK_ONLY),
    )
  }

  @Test
  fun `ready support copy does not promise an unsupported hardware axis`() {
    assertEquals(
      "Choose a performance profile.",
      tweakerProfileSupportingText(TweakerUtilityAvailability.READY),
    )
  }

  @Test
  fun `held ready copy wins over qualification while ordinary ready and discovered copy stay stable`() {
    assertEquals(
      "Profile changes are paused. Tweaker recovery has not finished.",
      tweakerProfileSupportingText(
        TweakerUtilityAvailability.READY,
        qualificationTestMode = true,
        heldByUnfinishedRestore = true,
      ),
    )
    assertEquals(
      "Choose a performance profile.",
      tweakerProfileSupportingText(
        TweakerUtilityAvailability.READY,
        heldByUnfinishedRestore = false,
      ),
    )
    assertEquals(
      "Sets the PRIME clock ceiling directly.",
      tweakerProfileSupportingText(
        TweakerUtilityAvailability.DISCOVERED_CEILING,
        discovered = TweakerDiscoveredCeiling(
          policyLabel = "PRIME",
          ceilingLabelCaps = "2.0 GHz",
          canWrite = true,
        ),
        heldByUnfinishedRestore = true,
      ),
    )
  }

  // The fan readout moved out of tweakerProfileSupportingText and into its own
  // tweakerProfileFanReadoutText : the row now composes the base subtext AND the
  // readout together rather than one replacing the other, so "replaces generic copy" is no longer
  // this function's behavior -- see TweakerRailRenderOrderTest for the row-level composition coverage.
  @Test
  fun `fan readout names duty cycle`() {
    assertEquals(
      "Fan 25%",
      tweakerProfileFanReadoutText(
        fanControl = com.gruning.mimir.rewrite.core.tweaker.TweakerFanControlReadout(
          profileId = "balanced",
          strategy = com.gruning.mimir.rewrite.core.tweaker.TweakerFanControlStrategy.HOLD_TARGET,
          targetTempC = 65,
          calibrationPowerReferenceMw = 11_250,
          dutyPercent = 25,
          rpm = 0,
        ),
      ),
    )
  }

  @Test
  fun `fan readout with live gpu temperature appends it after fan speed`() {
    assertEquals(
      "Fan 25% · GPU 62°C",
      tweakerProfileFanReadoutText(
        fanControl = com.gruning.mimir.rewrite.core.tweaker.TweakerFanControlReadout(
          profileId = "balanced",
          strategy = com.gruning.mimir.rewrite.core.tweaker.TweakerFanControlStrategy.HOLD_TARGET,
          targetTempC = 65,
          calibrationPowerReferenceMw = 11_250,
          dutyPercent = 25,
          rpm = 0,
        ),
        gpuTempC = 62,
      ),
    )
  }

  @Test
  fun `fan readout with no gpu temperature omits the field rather than a placeholder`() {
    assertEquals(
      "Fan 25%",
      tweakerProfileFanReadoutText(
        fanControl = com.gruning.mimir.rewrite.core.tweaker.TweakerFanControlReadout(
          profileId = "balanced",
          strategy = com.gruning.mimir.rewrite.core.tweaker.TweakerFanControlStrategy.HOLD_TARGET,
          targetTempC = 65,
          calibrationPowerReferenceMw = 11_250,
          dutyPercent = 25,
          rpm = 0,
        ),
        gpuTempC = null,
      ),
    )
  }

  @Test
  fun `pm uncertainty never becomes install`() {
    val identity = TweakerPairIdentity(TweakerPairState.PACKAGE_MANAGER_ERROR)
    assertEquals(
      TweakerUtilityAvailability.UNAVAILABLE,
      tweakerUtilityAvailability(identity.installedTruth, capabilities = null),
    )
  }

  private fun eligiblePresentFacts(
    signatureResult: Int = PackageManager.SIGNATURE_MATCH,
    service: TweakerServiceContractFacts? = TweakerServiceContractFacts(
      exported = true,
      permission = TweakerSession.CONTROL_PERMISSION,
    ),
    componentEffectivelyEnabled: Boolean = true,
    controlPermissionGranted: Boolean? = true,
  ): TweakerPairInspectionFacts = TweakerPairInspectionFacts(
    packageLookup = TweakerPackageLookupResult.Present(
      applicationEnabled = true,
      service = service,
      componentEffectivelyEnabled = componentEffectivelyEnabled,
    ),
    signatureResult = signatureResult,
    controlPermissionGranted = controlPermissionGranted,
  )
}
