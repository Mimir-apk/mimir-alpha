# TW-01 RP5 calibration and rollback specification

> Historical design record. Referenced device captures and `evidence/` records are not
> included in the public source. They do not establish current hardware acceptance.
> Normal discovered profiles do not require legacy qualification. For current usage and
> APK signing, see [README](README.md) and [SIGNING](SIGNING.md).

## Scope

TW-01 adds the machinery needed to collect and reject calibration evidence safely. It does not enable CPU, GPU, fan, or power mutation. `HARDWARE_WRITES_COMPILED=false` remains a release invariant for `0.3.0-tw02a`.

The only eligible target is the exact Retroid Pocket 5 hardware contract discovered at runtime:

- manufacturer `Moorechip`;
- model `Retroid Pocket 5`;
- SoC `SM8250`;
- CPU policies `0`, `4`, and `7` with their full OPP tables;
- Adreno KGSL power-level and frequency nodes;
- RP5 PWM fan nodes;
- 60 Hz display mode;
- PServer binder present.

The calibration identity is a SHA-256 digest over device identity, Android build fingerprint, CPU topology/OPPs, GPU OPPs, and display modes. A firmware or OPP change therefore invalidates prior evidence.

## Implemented acceptance gates

A report can reach `CERTIFIED` only when all conditions hold:

1. The observation completes normally for at least 300 seconds.
2. At least 270 one-second samples are present.
3. CPU, GPU, battery temperature, battery power, and Android thermal status are available.
4. Every power sample is captured while discharging.
5. CPU and GPU stay at or below 85 C.
6. Battery stays at or below 45 C.
7. Android thermal status never exceeds `MODERATE`.
8. The requested workload package is attested in the foreground.
9. At least 90% of all samples contain trustworthy foreground frame telemetry.
10. The exact candidate plan is exercised through the protected write path.
11. At least three rollback drills succeed with exact read-back.
12. Rollback successes equal rollback attempts.
13. Restore after process death/reboot is verified.

Threshold breaches or interrupted observations become `FAILED`. Complete observations missing non-thermal certification evidence become `OBSERVED`. An active session is `OBSERVING`; no session is `NOT_STARTED`.

## Required physical calibration matrix

Each candidate profile must be tested separately against representative workloads while unplugged:

| Profile | CPU-heavy | GPU-heavy | Mixed emulator | Sustained game | Minimum runs |
|---|---:|---:|---:|---:|---:|
| Quiet | 5 min | 5 min | 5 min | 15 min | 3 per workload |
| Balanced | 5 min | 5 min | 5 min | 30 min | 3 per workload |
| Performance | 5 min | 5 min | 5 min | 30 min | 3 per workload |

Stock is the restore reference, not a reduced-power certification target. Every profile run must preserve display/input/audio behavior and record workload identity, frame pacing, temperatures, power, clocks, fan RPM, throttling, and exit reason. TW-01C records the hardware telemetry subset and Usage Access-based foreground workload attestation. TW-02A adds fail-closed SurfaceFlinger presentation telemetry; representative active-gameplay and repeated profile-matrix runs remain owed.

## TW-01C first workload observations

On the exact RP5 hardware fingerprint, one unplugged 300-second `balanced` observation
completed for PPSSPP / God of War: Ghost of Sparta and one for Eden / Pokemon Legends
Z-A. Both passed the duration, sample coverage, telemetry, discharging, temperature,
thermal-status, and foreground-attestation gates. Both remained `OBSERVED`, as required,
because writes, rollback drills, and reboot restore were absent. These are first-run
observations, not profile certification or completion of the required physical matrix.

The historical observation report and raw XML traces are not distributed with this source.

## Rollback sequence for a future write-enabled lab build

1. Confirm the exact hardware fingerprint and a clean, absent active snapshot.
2. Capture CPU min/max, KGSL min/max power levels, fan mode, and the original node modes.
3. Persist the snapshot synchronously; abort if persistence fails.
4. Apply one candidate atomically through the single PServer command boundary.
5. Read back CPU, GPU, and fan targets.
6. On any error or mismatch, restore and read back the complete snapshot.
7. Retain the snapshot until restore verification and durable snapshot clearing both succeed.
8. Repeat fault-injected rollback at least three times.
9. Repeat with service process death and device reboot while a snapshot is active.

TW-01 hardens steps 3, 5, 6, and 7 in code and tests. Steps involving real writes remain intentionally unexecuted.

## AutoTDP boundary

AutoTDP is not a profile alias. A future feedback controller may be evaluated only after fixed Quiet, Balanced, and Performance profiles pass the matrix. It additionally requires a trustworthy frame-time signal, bounded update cadence, hysteresis, thermal override, per-game limits, and immediate restoration on loss of workload identity. No automatic controller is included in TW-01.
