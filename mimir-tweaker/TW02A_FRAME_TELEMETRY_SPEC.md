# TW-02A RP5 foreground frame telemetry specification

> Historical design record. Referenced device captures and `evidence/` records are not
> included in the public source. They do not establish current hardware acceptance.
> Normal discovered profiles do not require legacy qualification. For current usage and
> APK signing, see [README](README.md) and [SIGNING](SIGNING.md).

## Scope

TW-02A adds a trustworthy frame-presentation signal to the standalone Mimir Tweaker
service. It remains read-only: `HARDWARE_WRITES_COMPILED=false`, dry-run cannot be
disabled, and no CPU, GPU, fan, display, or power target is applied.

The signal is grounded in Android SurfaceFlinger presentation timestamps. Android's game
performance guidance defines game FPS from the time between frames drawn on app-owned
surfaces and documents SurfaceFlinger timestats as the corresponding measurement tool.
On the target RP5 firmware, both the per-layer timestats output and the lower-overhead
`--latency` presentation ring are available to ADB shell. The TW-02A APK must separately
prove that its existing PServer transport can return the same read-only data.

## Source contract

For each telemetry sample while the requested workload is foreground:

1. Resolve SurfaceFlinger layers with `dumpsys SurfaceFlinger --list`.
2. Accept exactly one highest-priority package-bound game layer:
   `SurfaceView[<package>/...](BLAST)#<id>`.
3. Reject no match or multiple equally ranked matches; never guess.
4. Read the selected layer with `dumpsys SurfaceFlinger --latency '<layer>'` through
   PServer. Because this RP5 PServer returns only the final physical stdout line, join
   the ring with a delimiter in read-only `awk`, then expand and parse it in Kotlin.
5. Treat column two as the actual presentation timestamp and require a strictly
   increasing ring with at least five intervals.
6. Reject a ring whose latest presentation is more than two seconds old.
7. Invalidate the cached layer after any latency failure so activity/layer recreation is
   resolved on the next sample.

Layer values are shell-quoted even though the resolver is package-bound. No command in
this path may contain sysfs writes, `chmod`, `echo`, or `settings put`.

## Recorded metrics

Each sample records:

- frame-source status and exact SurfaceFlinger layer name;
- rolling average FPS in thousandths;
- p50 and p95 presentation interval in microseconds;
- percentage of intervals slower than 50 ms (20 FPS threshold);
- percentage of intervals slower than 34 ms (30 FPS threshold).

The report records available and foreground-attested frame sample counts, median rolling
FPS, maximum rolling p95 interval, average slow-frame percentages, and distinct layer
names. Raw timestamp rings are deliberately not persisted because adjacent samples
overlap; the derived sample metrics remain auditable without multiplying report size.

## Acceptance and failure posture

- At least 90% of all observation samples must both attest the requested foreground
  package and contain an `AVAILABLE` frame reading.
- Insufficient frame coverage adds a certification blocker.
- Missing PServer, command failure, missing/ambiguous layer, malformed timestamps, and
  stale timestamps are explicit statuses and cannot fall back to Tweaker's own window
  timing, guessed FPS, emulator overlays, or ADB-injected values.
- Emulator overlays may be used only as a visual cross-check during device validation.
- TW-02A does not make frame-rate stability, subjective smoothness, fixed-profile safety,
  rollback, reboot restore, or AutoTDP claims.

## Device validation matrix

1. Install `0.3.0-tw02a` on the exact RP5 hardware fingerprint.
2. Run a short transport smoke observation for Eden and confirm the APK itself persists
   `AVAILABLE` frame readings.
3. Run unplugged five-minute observations for PPSSPP / God of War: Ghost of Sparta and
   Eden / Pokemon Legends Z-A.
4. Compare reported FPS against the emulator's visible overlay where available.
5. Confirm duration, workload attestation, frame attestation, temperatures, power, and
   Android thermal status.
6. Confirm both reports remain `OBSERVED` because protected writes, rollback drills, and
   reboot restore are still absent.

## AutoTDP boundary

This phase supplies an input signal only. It does not implement a controller. A later
controller still requires fixed-profile certification, bounded cadence, hysteresis,
unreachable-target detection, thermal override, per-game limits, and immediate restore
on workload or frame-signal loss.

## Physical result

The exact RP5 completed unplugged 300-second rendered-title/menu observations for Eden /
Pokemon Legends Z-A and PPSSPP / God of War: Ghost of Sparta. Frame coverage was 282/288
(97.9%) at a 30.003 FPS median for Eden and 338/338 (100%) at a 60.006 FPS median for
PPSSPP. Both reports remained `OBSERVED`; hardware writes, rollback drills, reboot restore,
active-gameplay variability, and repeated runs remain owed. See
the historical frame-observation report, which is not distributed with this source.

Mimir-rewrite remains side-by-side and is not a replacement candidate yet.
