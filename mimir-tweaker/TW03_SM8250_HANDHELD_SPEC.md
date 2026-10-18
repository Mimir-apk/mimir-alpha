# TW-03 SM8250 handheld contract and certification

## Scope

TW-03 generalizes Mimir Tweaker from an exact Retroid Pocket 5 model gate to the **SM8250
handheld class**: Android gaming handhelds that expose Qualcomm SM8250 cpufreq/KGSL nodes
and the vendor `PServerBinder` write boundary (Retroid, AYN, and similar firmware).

Arbitrary Snapdragon 865 phones without PServer remain **unsupported for mutation**. They
may be probed in the future, but TW-03 does not certify or enable writes on them.

`HARDWARE_WRITES_COMPILED=false` remains the default **safe** product flavor. A separate
**lab** flavor compiles the protected write path for on-device calibration only.

## Read contract (`CALIBRATION_REQUIRED`)

All of the following must be discovered at runtime:

1. SoC model `SM8250` (`ro.soc.model` or `ro.vendor.qti.soc_model`).
2. `PServerBinder` present.
3. Adreno KGSL `min_pwrlevel` / `max_pwrlevel` nodes and at least two GPU OPP frequencies.
4. At least three cpufreq policies, each with a non-empty `scaling_available_frequencies`
   table (policy IDs are device-specific, not hard-coded).

Optional capabilities (never block the read contract):

- PWM fan sysfs nodes (profile-specific; RP5 uses `gpio5_pwm2`).
- `settings put system fan_mode` (vendor fan mode when sysfs fan is absent).
- Display refresh rates (telemetry and fingerprint only).

## Device profiles

| Profile id | Selection | CPU/GPU | Fan |
|---|---|---|---|
| `rp5` | Moorechip + `Retroid Pocket 5` | yes | `gpio5_pwm2` + fan_mode |
| `generic-sm8250-pServer` | SM8250 + PServer fallback | yes | no |

Additional handheld profiles are added only with grounded sysfs evidence; they are not
inferred from SoC alone.

## Supported tuning axes

Capabilities expose `supportedAxes` as a subset of `cpu`, `gpu`, `fan`. Profile plans omit
fan targets when `fan` is not supported.

## Hardware fingerprint

`HardwareFingerprint` is a SHA-256 digest over manufacturer, model, SoC, Android build
fingerprint, CPU topology/OPPs, GPU OPPs, display modes, and `deviceProfileId`. Firmware or
OPP changes invalidate prior evidence.

## Certification model

Unchanged from [TW01_CALIBRATION_SPEC.md](TW01_CALIBRATION_SPEC.md): certification is per
`(hardwareFingerprint, profileId)` with the exact candidate plan hash. A device reaches
`SUPPORTED` when `quiet`, `balanced`, and `performance` are all `CERTIFIED` for its
current fingerprint.

## Lab write flavor

The **lab** product flavor sets `HARDWARE_WRITES_COMPILED=true`. Dry-run can be disabled
only in lab builds. Protected apply remains blocked until `CertificationStore` marks the
exact plan certified.

Lab-only Binder API v3 methods (append-only):

- `runRollbackDrills(profileId, packageName, drillCount)`
- `recordLabSafetyEvidence(writesExercised, rollbackAttempts, rollbackSuccesses, rebootRestoreVerified)`

## Mimir integration

Mimir rewrite binds to `com.gruning.mimir.tweaker/.MimirTweakerService` using the
`contract` AAR. Both APKs must share a signing certificate (`CONTROL_TWEAKER` is
signature-protected). On game launch Mimir calls `applyProfile`; on return to Mimir it
calls `restore()`.
