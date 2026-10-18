# WAVE2 thermal-derate specification (historical reconstruction)

> Public-source scope, 2026-10-18: this is an archived development record, not current
> installation guidance or hardware acceptance. References to qualification requests,
> issued manifests, device captures and private history describe evidence available to
> the original author; those artifacts are not distributed with this public source.
> Historical `[SIGNED]` and `[DEVICE-VERIFIED]` labels are not reproducible public evidence.
> Read “today”, “current” and “in this tree” below in that historical context. Source line
> numbers refer to earlier revisions. Normal discovered profiles do not need qualification;
> see [README](README.md) and current [APK signing](SIGNING.md).

The original thermal specification was unavailable when this reconstruction was written.
The search statements below describe that earlier development checkout, not the public
repository's current Git history.

What follows is a **new** document, assembled section-by-section from what the citing code and
tests say their own cited section contains, cross-checked against the code they annotate. Every
substantive claim below carries one of these provenance tags:

- `[DESIGN-CONSTRAINT]` — a technical requirement recorded in source comments.
- `[SIGNED]` — a historical claim about private qualification material, including a
  sequence-9 request. That request and issued artifacts are not included here; neither
  source definitions nor the historical label establish a publicly verified signature.
- `[CODE-VERIFIED]` — verified against the source code and/or tests in this tree, but NOT against
  a signed artifact and NOT against a device capture. This is the tag for "the code provably does
  what the claim says," without establishing hardware behavior or an artifact signature.
- `[DEVICE-VERIFIED]` — the original author reported a device capture; it is not included here.
- `[UNVERIFIED]` — the source text asserts device verification but no evidence file could be
  located to back it.
- `[EXTRAPOLATED]` — a proposed value without device validation, per the source comments.
- `[OWED]` — a live debt, currently unresolved.
- `[UNKNOWN]` — could not be established from anything in this tree.

Hybrid slash markers used in an earlier pass of this document (e.g. `[SIGNED]/code-verified`,
`[SIGNED]/code-grounded`) are not defined in this glossary and have been removed; every claim
below now carries exactly the tag(s) defined above.

Two siblings are cited alongside this one and are **not** reconstructed here:
`SEQ9_PROFILE_RETUNE_SPEC.md` and `WAVE1A_SPEC.md` (citations resolved 2026-08-09) are also missing
from every ref and from disk. Do not assume this document covers their content — where SEQ9 or
WAVE1A material is unavoidable context for a WAVE2 claim (e.g. the SEQ9 nominals a WAVE2 rung is
built on), it is cited by commit/file, never invented.

Where a citation gives only a bare section number with no describable content, it is listed under
"Sections cited but unrecoverable" rather than guessed at.

---

## S1 — the measurement behind "the last Guardian-certified state is cooler than Stock"

Cited twice, with content, in two different files — a prior pass of this document missed both
citations and asserted no section beyond S2 was cited with content anywhere in the tree. That was
wrong, and is corrected here.

1. `HardwareSessionSupervisor.kt:853` (inside the `Derate` actuation branch's comment on why there
   is deliberately no restorer fallback for actuation failures): "...leaves the session at its
   last Guardian-certified state, which spec S1's own measurement shows is unconditionally cooler
   than Stock..."
2. `HardwareSessionSupervisorTest.kt:1550`, inside the test
   `` `successful derate refreshes the in memory applied target contract so the next tick sees no
   false drift` `` (line 1512): "...reproducing the exact defect WAVE2 fixed (spec S1: 76.9 C ->
   104.5 C mean)."

Read together, S1 appears to be a paired comparison measurement: a Guardian-certified/derated
state at a ~76.9 C mean against an unguarded Stock state at a ~104.5 C mean — the basis for the
"unconditionally cooler than Stock" claim in citation 1. The 104.5 C figure also appears
independently in `HardwareSessionSupervisor.kt:44` (part of the S3.3-context kdoc, not itself
labelled S1 there): "its post-rollback Stock regime (no Mimir guard at all) ran CPU to a 104.5 C
*mean*." The two 104.5 C citations are numerically identical and plausibly the same underlying
measurement, but nothing in this tree states that explicitly, so that connection is offered as an
observation, not asserted as fact.

**Note on attribution:** an earlier draft of this document discussed the 104.5 C figure under S2
while simultaneously claiming no section beyond S2 was cited with recoverable content anywhere in
this tree. Both were wrong — the test comment at `HardwareSessionSupervisorTest.kt:1550` names
this figure as S1's, not S2's. It has been moved here.

Same evidentiary posture as S2 below: no raw capture backing either the 76.9 C or 104.5 C figure
was located in this search. The qualitative claim ("the last Guardian-certified state is
unconditionally cooler than Stock") is `[CODE-VERIFIED]` — it is what the supervisor's own comment
asserts, and is internally consistent with the S3.3-context kdoc's independent 104.5 C figure. The
specific numbers themselves (76.9 C, 104.5 C mean) are `[UNVERIFIED]` — asserted in comments, not
backed by a citable evidence file here.

## S2 — the incident WAVE2 responds to

`SupervisorPolicy.kt` (`HardwareSessionSupervisor.kt:39-52`) states directly what S2 shows:
raising the CPU/GPU trip threshold alone would **not** have prevented the incident that motivated
WAVE2 — the release that night was `THERMAL_ANDROID` at `SEVERE`, and CPU never crossed even the
old 95 C trip (peak 98.4 C, filtered out by the then-current debounce). `[DESIGN-CONSTRAINT]` (dated
2026-08-06, WAVE2 spec S3.3 citation) — the fix S2 argues for is a **response-type** change
(`SupervisorDecision.Derate`, never `Restore`, for all four thermal signals), not a threshold
tweak. This is corroborated by the WAVE2 Stage A commit itself:

> `1260eedc` — "WAVE 2 Stage A: thermal events derate instead of releasing to Stock" (2026-08-06)

`[CODE-VERIFIED]`, not `[DEVICE-VERIFIED]`: no evidence file for the S2 incident itself (e.g. a
raw capture of the `THERMAL_ANDROID`/`SEVERE` release) could be located in this tree — the
95 C-peaked-at-98.4 C figure is asserted in the `SupervisorPolicy` kdoc, not backed by a citable
evidence path here. Treated as `[UNVERIFIED]` for the specific numeric claim ("peak 98.4 C")
pending a locatable trace; the qualitative claim ("CPU trip did not fire that night; the release
was `THERMAL_ANDROID`") is corroborated by the surrounding code structure (androidThermalTrip is a
distinct, unaffected signal path) and is treated as `[CODE-VERIFIED]`. (The "104.5 C mean" figure
formerly discussed here has been moved to S1 above, where the tree actually attributes it.)

## S3.1 — a thermal signal never produces Restore

Recovered directly, and verified against the code it annotates. `HardwareSessionSupervisor.kt`
states the rule verbatim in `SupervisorDecision.Derate`'s kdoc (lines 330-336):

> "WAVE2_THERMAL_DERATE_SPEC.md S3.1: a thermal signal never produces `Restore`. It produces one
> step down the pre-authorised `ThermalDerateLadder`, staying inside the active (already-qualified)
> profile."

Verified in `tick()` (`HardwareSessionSupervisor.kt:705-717`): a present thermal outcome always
returns `SupervisorDecision.Derate`, never `Restore`. At the floor and still over trip,
`classifyAndAdvanceLadder` (line 1333-1339) returns `null` and the tick falls through to
`Continue` — "remain," never `Restore`. Test coverage: `HardwareSessionSupervisorTest.kt:1417`,
`` `WAVE2 test 5 at the floor and still over trip the decision is remain never Restore` ``.
`[CODE-VERIFIED]`.

## S3.2 — ownership/integrity failures outrank thermal signals

Two separate comments in `HardwareSessionSupervisorTest.kt` carry this claim, and a prior pass of
this document merged them into a single quote misattributed to one line. They are kept separate
here:

- `HardwareSessionSupervisorTest.kt:1436-1437` (comment above
  `` `WAVE2 test 9 ownership and integrity failures still produce a full Restore unchanged` ``):
  "ownership/integrity failures are NOT thermal signals and must still produce a full Restore to
  Stock, completely unchanged."
- `HardwareSessionSupervisorTest.kt:1474-1476` (comment inside the same test, immediately above its
  final assertion block): "Even a simultaneous CRITICAL thermal reading does not turn an integrity
  failure into a derate -- ownership loss is checked before telemetry is even read (spec S3.2's
  'we no longer know we own the device' outranks a confirmed-hot reading)."

Verified in `tick()`: owner-liveness, hard-lease, and lease-deadline checks (lines 668-689) all
execute and can return `Restore` **before** the telemetry sample is even read (line 693). The test
explicitly exercises a simultaneous CRITICAL thermal reading with a dead owner and asserts
`OWNER_TOKEN_DIED` wins (`HardwareSessionSupervisorTest.kt:1477-1483`, inside the same test).
`[CODE-VERIFIED]`.

## S3.3 — the new 105 C trip, and the immediate critical band

Three things are recoverable here, all cross-checked against `SupervisorPolicy`:

1. **The threshold itself.** CPU/GPU readings above 105 C initiate downclocking
   (`SupervisorPolicy.kt:39-45`). `[DESIGN-CONSTRAINT]`. This supersedes WAVE1C's 95 C
   vendor-calibration value. 105 sits 10 C below the vendor's `hot` threshold (115 C, read from
   Odin 3 sysfs 2026-08-06) rather than exactly on the platform's own first passive-mitigation
   point (also 95 C). Pinned by the test named
   `` `cpu and gpu trip constants are pinned 10 C below the vendor's hot threshold` ``
   (`HardwareSessionSupervisorTest.kt:240`). `[CODE-VERIFIED]`: `cpuTripCExclusive = 105`,
   `gpuTripCExclusive = 105` (`SupervisorPolicy.kt:56-57`) — retagged down from a prior `[SIGNED]`:
   trip temperatures are not fields of the signed manifest, which signs kHz / GPU level / fanMode
   only (`QualificationManifest.kt:95-101`), so this is provable from source, not from a signed
   artifact.

2. **An immediate critical band, independent of dwell.** `SupervisorPolicy.kt:68-75`: any single
   valid CPU or GPU sample above `thermalCriticalC = 112` (3 C below the vendor's 115 C `hot`
   trip) derates **two** rungs immediately, no debounce. This is distinct from the sustained-trip
   mechanism in S3.4. `presentThermalCondition` (`HardwareSessionSupervisor.kt:1229-1261`)
   implements exactly this: CRITICAL is checked before the rolling-mean TRIP check, per sensor, in
   ANDROID → CPU → GPU → BATTERY order. Test:
   `` `reading above the critical band derates two rungs immediately on the first sample` ``
   (`HardwareSessionSupervisorTest.kt:289`) — annotated in-line as "WAVE1A_SPEC.md test 3,
   WAVE2-updated (spec S5 test 4)". `[CODE-VERIFIED]` (same retag rationale as above: trip/critical
   temperatures are not signed manifest fields).

   Android and battery are explicitly **not** touched by this recalibration — `SupervisorPolicy.kt`
   states S3.3 "changes CPU/GPU's *response*, not their sensitivity, but leaves android/battery's
   filter mechanism unchanged too ('change its response, not its sensitivity'; battery has no
   vendor trip points to recalibrate the filter against either)." `[DESIGN-CONSTRAINT]`, `[CODE-VERIFIED]`.

3. **Battery's own critical band, previously undocumented here.** `HardwareSessionSupervisorTest.kt:306-311`
   (comment above
   `` `battery above the sane maximum derates two rungs immediately battery trip band still needs
   three` ``): battery above 800 deci-C still trips immediately — mapped, by the same analogy as
   CPU/GPU's critical band, to a 2-rung derate; battery in the (450, 800] range still needs three
   consecutive samples (mechanism unchanged from WAVE1A) and derates one rung. This is a WAVE2
   behaviour change (neither branch releases to Stock any more) that no earlier pass of this
   document mentioned. `[CODE-VERIFIED]`.

## S3.4 — the 2-second rolling mean replaces consecutive-streak debounce (CPU/GPU only)

Recovered from `SupervisorPolicy.kt:58-67` and corroborated by three tests. The mechanism: for CPU
and GPU only, per-sample consecutive-streak counting is replaced by a 2-second rolling mean
(`thermalRollingWindowMs = 2_000L`): `mean(samples in last 2000 ms) > trip` derates one rung.
Android and battery keep the WAVE1A consecutive-streak counters unchanged (`thermalTripDebounceSamples
= 3`) — this is the same S3.3 "response, not sensitivity" split.

The motivating measurement, quoted directly from the source: "Measured 2026-08-06: CPU oscillated
83-102 C across ~300-350 ms samples, and `thermalTripDebounceSamples = 3` (three *consecutive*
samples) almost never survives that oscillation — the debounce added in WAVE1a to stop false trips
became capable of suppressing real ones." `[UNVERIFIED]` for the specific 83-102 C oscillation
figure — no evidence file with that exact range was located, though it is consistent in character
with the real capture backing S5 test 3 below. The comment itself also states plainly: "The 2 s
window is a spec starting value, not a measured optimum" — i.e. `thermalRollingWindowMs` is itself
`[EXTRAPOLATED]`, not measured as optimal.

Tests: `` `WAVE2 test 1 a sustained trace above trip derates exactly one rung not a release` ``
(line 254), `` `WAVE2 test 2 a single spike diluted by real history does not derate` `` (line 273),
and the regression proof, `` `WAVE2 test 3 real oscillating trace that hugs the trip line derates
the debounce regression proof` `` (line 442) — see S5 below for the real-capture detail on that
last one. `[CODE-VERIFIED]` mechanism; `[UNVERIFIED]` on the specific oscillation-range number
quoted in the comment.

## S3.5 — the ladder is one shared physical rung position; policy0 pinning; the CPU+GPU pairing requirement

Three distinct claims live under S3.5 across two files:

1. **One abstract rung position across four thermal channels**, not four independent per-sensor
   ladders — "one physical CPU+GPU state" (`ThermalDerateLadder.kt:144`,
   `HardwareSessionSupervisor.kt` private field comment at line 486-489). `classifyAndAdvanceLadder`
   (`HardwareSessionSupervisor.kt:1333`) confirms: whichever sensor trips first advances the same
   `thermalRung` field. `[CODE-VERIFIED]`.

2. **`policy0` is pinned at its own profile's nominal on every rung** — the design identifies
   the prime cluster (`policy6`) as the hotspot; whether `policy0` should ever join the ladder
   depends on OWED per-cluster thermal attribution (carried forward to S3.5/S7 below).
   `ThermalDerateLadder.kt:22-27`: "Structural, not conventional: `ladder` pins every rung it
   builds to the `policy0MaxKhz` value passed in once, so no entry can silently drift off its own
   profile's clamp." Verified by test
   `` `policy0 never moves from its own profiles nominal on any rung` `` (`ThermalDerateLadderTest.kt:47`).
   `[DESIGN-CONSTRAINT]` for the *decision* to pin policy0; `[CODE-VERIFIED]` for the mechanism.

3. **CPU and GPU step down together.** The initial target was approximately 2.8 GHz
   for the prime CPU cluster and 967 MHz for the GPU (`ThermalDerateLadder.kt:10-14`).
   The intent is to reduce heat without large single-domain swings in frametime.

   This supersedes an earlier CPU-first/GPU-last draft. The rationale given: CPU and GPU share one
   thermal/power envelope, so clamping CPU alone frees budget the GPU immediately spends — a
   CPU-only step is close to a thermal no-op, and large single-domain swings read as frametime
   microstutter. Every rung is therefore a pair, moved together. The comment is explicit that this
   does **not** contradict "WAVE 1b's 'GPU is protected, CPU is the donor'" — that governs
   power-envelope *allocation*; this governs thermal *derate* (shedding heat from a shared
   envelope). `[DESIGN-CONSTRAINT]`.

   Note: the illustrative target names "2.8 GHz" and "967 MHz" as illustrative figures; the actual signed
   rung-1 values in the ladder are 2,841,600 kHz (≈2.84 GHz) on `policy6` and GPU level 2 (not a
   MHz figure — the GPU axis is an inverted power-level index, 0=1100 MHz fastest through
   13=160 MHz slowest, per `ThermalDerateRung`'s own kdoc at `ThermalDerateLadder.kt:51-53`). The
   illustrative target and the signed numeric rung are close but not literally identical strings; both
   are reproduced here rather than silently reconciled.

## S4 — the "mechanism gap," and why it is now CLOSED

`ThermalDerateLadder.kt:5-8` still reads, unedited:

> "Stage A (decision layer) only: `HardwareSessionSupervisor` decides WHICH rung to move to;
> nothing in this module writes these values to hardware yet — see spec S4, 'the mechanism gap':
> no sanctioned path exists for a write more conservative than the qualified plan."

**This is stale and must be corrected here, precisely.** Two separate claims are bundled in that
sentence, and only one is still true:

- *"Nothing in this module writes these values to hardware"* — still literally true. This is a
  data-only file (`ThermalDerateLadder.kt` defines `ThermalDerateRung`/`ThermalDerateProfileLadder`
  and nothing else); it contains no write path and never has.
- *"No sanctioned path exists for a write more conservative than the qualified plan"* — **false as
  of `b02fed93`** ("WAVE 2 Stage B: actuate the derate by signing the ladder rungs (schema v3)",
  2026-08-06). Stage B signs the ladder rungs into `QualificationManifest` schema v3
  (`QualifiedDerateRung`, `QualificationManifest.kt:95-141`; admission rules in
  `QualifiedProfilePlan.init`, lines 190-249) and wires actuation through
  `TransactionCoordinator.applyDerate` (`TransactionCoordinator.kt:1304-1550`) and
  `HardwareSessionSupervisor.execute`'s `is Derate` branch (lines 798-857). A signed rung **is**
  exactly the sanctioned, more-conservative-than-nominal write path S4 said did not exist.
  `MimirTweakerService.kt:2463` marks the actuation point directly: "execute (WAVE2 Stage B: derate
  is now actuated)."

  **Important qualifier, added here (see the Live debts section below): the mechanism exists and
  is wired, but as of this tree's only signing artifact, it is not populated with any rungs for
  any profile.** "The write path exists" and "the write path is exercised on the reference device
  today" are two different claims; only the first is established by this section.

So: S4's mechanism gap is **CLOSED** as a code/schema matter, closed by `b02fed93`, and the
surviving file-level comment in `ThermalDerateLadder.kt` is stale on the "no sanctioned path" half
specifically — not wrong about this module's own scope, which remains data-only. `[CODE-VERIFIED]`;
the correction itself is `[CODE-VERIFIED]`, not `[DESIGN-CONSTRAINT]` — it follows from reading the
commit and the code it introduced, not from a design requirement.

**The domination argument** that makes signing rungs safe "without re-signing each rung" has four
independently-provable rules, not two — a prior pass of this document called two rules
"recoverable in full," which undercounted. Recoverable in full from
`QualificationManifest.kt:190-249` and cross-checked by the Java generator
(`QualificationManifestGenerator.java:252-298`):

- **Rule 1**: every rung is dominated by the base plan itself — CPU slower-or-equal per policy,
  GPU higher-or-equal (inverted scale), fan mode identical. Checked against the fixed nominal,
  not the previous rung. (`QualificationManifest.kt:195-223`;
  `QualificationManifestGenerator.java:259-275`.)
- **Rule 2**: rungs are ordered and monotonically slower — each rung no faster than the one
  immediately before it. Checked only between adjacent rungs, never chained from the base plan.
  (`QualificationManifest.kt:224-249`; `QualificationManifestGenerator.java:276-297`.)
- **Rule 3**: each rung's own `planSha256` must equal the hash of that rung's own
  `(cpuMaxKhz, gpuMaxPowerLevel, fanMode)` — a plan-identity check on the rung itself, distinct
  from rules 1/2's cross-rung domination checks. Enforced in `QualifiedDerateRung`'s own `init`
  block (`QualificationManifest.kt:117-119`); the Java generator's own comment names this rule
  explicitly as already covered by "`RungPlan`'s own compact constructor... planSha256 identity is
  computed, never carried" (`QualificationManifestGenerator.java:252-255`).
- **Rule 4**: each rung's `evidenceIndexSha256` and `guardianRollbackEvidenceSha256` must be
  non-zero digests (not all `'0'` characters) — evidence is mandatory per rung, not inherited from
  the base plan. Enforced in the same `init` block (`QualificationManifest.kt:115-116`); the same
  generator comment names this as "evidence is mandatory" (`QualificationManifestGenerator.java:252-255`).

Rules 1-2 are deliberately kept separate from each other (the code's own comment explains why —
collapsing them would make rule 1 mathematically redundant and impossible to revert independently
of rule 2), and rules 3-4 are deliberately kept separate from rules 1-2 by living in the rung's own
init rather than the cross-rung admission block. All four throw (rejecting the whole manifest
payload) on violation — "an invalid entry rejects the complete bundle" decode posture, per the
class kdoc at `QualificationManifest.kt:86`. `[CODE-VERIFIED]`.

## S4.3 — fan-only reassert never falls back to Restore

Not part of the thermal-derate ladder itself, but cited alongside S4 in this tree's commit trail
and cited with recoverable content, so it is reconstructed here rather than left in the
unrecoverable list (a prior pass of this document did the latter while its own commit table
already named the fan-lane commits — a self-contradiction, corrected here).

`SupervisorPolicy.kt` (`HardwareSessionSupervisor.kt:89-95`): "S4.3: a fan-ONLY drift (a charger
plug/unplug rewriting `fan_mode` at an unpredictable time is normal operation on this device, not
tampering) re-asserts within this same bounded-window/burst shape [as CPU target repair] -- but
unlike [`targetRepairWindowMs`]'s CPU repair, exhaustion degrades to
[`SupervisorDecision.Continue`], never [`SupervisorDecision.Restore`]: a fan-only cause must never
cost the compute profile, on the first attempt or the last." Backed by
`fanReassertWindowMs = 30_000L`, `maxFanReassertAttemptsPerWindow = 4`,
`fanReassertBurstWindowMs = 5_000L`, `maxFanReassertAttemptsPerBurst = 2`
(`HardwareSessionSupervisor.kt:96-99`).

Verified in `execute()`'s `is RepairFanTarget` branch (`HardwareSessionSupervisor.kt:859-864`):
"S4.3: re-assert the SAME already-signed fanMode via `GenerationBoundFanReasserter` -- no new
target is adopted, unlike Derate's new rung. This mirrors Derate's posture, not RepairCpuTarget's
below: a fan-only cause must never fall back to Restore, on this attempt or any other
admitFanReassert grants, so there is deliberately no restorer fallback here regardless of outcome."
This is the same no-restorer-fallback posture as S3.1's thermal Derate and the actuation-failure
handling in S4/the Live debts section below — three independent causes (thermal derate, derate
actuation failure, fan-only drift) all share the rule that they degrade to "remain," never
"restore." `[CODE-VERIFIED]`.

Dated commits `95f6fa7b` (2026-08-06, "Fan ownership via mode 5 (SPORT); fan drift stops costing
the profile") and `878ba063` (2026-08-06, "Fan lane: close the composed-path Restore, wire the
reasserter, extend the Guardian web") — see the commit table below — implement this mechanism; it
is a sibling to thermal derate, not part of the thermal-derate ladder itself, but is in-scope here
because S4.3 is cited with recoverable content in the same files S4 is.

## Recovery (cited in-tree as S3.4)

A prior pass of this document titled this section "S3.6-ish" and invented that label — nothing in
this tree cites an S3.6. The recovery comment below cites **spec S3.4** explicitly, and a prior
pass of this document even quoted that citation correctly before filing the section under a
contradicting invented heading. Corrected here.

`HardwareSessionSupervisor.kt:486-489` (comment on the `thermalRung` field): "WAVE2 S3.1/S3.5: this
session's current position on `ThermalDerateLadder`. 0 == nominal (never derated);
`ThermalDerateLadder.maxRung` == the floor. Monotonically non-decreasing in Stage A -- recovery
(stepping back up after sustained cooling, spec S3.4) is not yet implemented; see unavailable development notes for
why that is deliberate, not an oversight."

Verified by exhaustive search: `thermalRung` is mutated in exactly two places in the whole file —
incremented in `classifyAndAdvanceLadder` (`+= appliedRungs`, line 1338) and reset to `0` only in
`resetTelemetryState()` (line 1360), which runs on full session adopt/clear, not on a cooling
tick. There is no code path that decrements it while a session is active. **No recovery mechanism
exists in this codebase today.** `[CODE-VERIFIED]` as a negative claim (absence).

The comment's own citation — "see unavailable development notes for why that is deliberate, not an oversight" —
points at a document this search could not locate anywhere in the tree, on any ref, or under
the original local search locations. **The stated rationale for why omitting recovery is
deliberate rather than an oversight is `[UNKNOWN]`.** This is a genuine gap, not smoothed over:
the code proves recovery doesn't exist; it does not prove why that's the right call, only that
someone asserted, unverifiably from here, that it was considered.

## S5 — the numbered tests

Six test numbers are cited against S5: 1, 2, 3, 4, 5, 9. All six were located and mapped to their
real test names and line numbers in `HardwareSessionSupervisorTest.kt`:

| S5 # | Real test name | Line | What it proves |
|---|---|---|---|
| 1 | `` `WAVE2 test 1 a sustained trace above trip derates exactly one rung not a release` `` | 254 | A session's first-ever telemetry sample, with no history to average, degenerates `mean(last 2s)` to that one sample; if already above trip, it derates on tick one. |
| 2 | `` `WAVE2 test 2 a single spike diluted by real history does not derate` `` | 273 | A single 106 C spike surrounded by real 80 C history stays diluted under the rolling mean and does not trip. |
| 3 | `` `WAVE2 test 3 real oscillating trace that hugs the trip line derates the debounce regression proof` `` | 442 | THE regression proof (see below). |
| 4 | `` `reading above the critical band derates two rungs immediately on the first sample` `` | 289 | S3.3's immediate CRITICAL band (112 C), no dwell, 2-rung derate on first sample — for both CPU and GPU. |
| 5 | `` `WAVE2 test 5 at the floor and still over trip the decision is remain never Restore` `` | 1417 | Two CRITICAL (2-rung) hits reach the 3-rung floor from nominal; a third still-critical sample remains at the floor, never restores. |
| 9 | `` `WAVE2 test 9 ownership and integrity failures still produce a full Restore unchanged` `` | 1448 | Owner death, hard-lease expiry, and boot-ID mismatch all still produce a full `Restore`, unchanged by WAVE2 — including that a simultaneous CRITICAL thermal reading does not turn an integrity failure into a derate. |

**Test 3 deserves its own note — it is annotated in-line as "the most important test in this
suite."** The test's own comment describes a real capture behind it: CPU samples truncated to
whole degrees exactly as the production telemetry path truncates
(`LivePerformanceTelemetry.kt`'s `normalizedThermalCelsius(raw).toInt()`, truncating toward zero),
attributed to an Odin 3 capture named `RE-RUN-1315.tsv` (tweaker build `0b2fbaf8...`, 2026-08-06),
sample indices 928-937: `105, 103, 105, 105, 105, 105, 106, 105, 105, 105`. The test's own comment
is explicit that this is **deliberately not** the illustrative "83.4/89.6/84.5/98.4/102.3" trace
that appears in the prose citing S3.4/S5 test 3 elsewhere — that snippet was independently checked
against the same capture and its own honest 2-second window never exceeds roughly 103 C mean even
before truncation, so it *cannot* regression-test the new 105 C trip. The window actually used in
the test is described as "a later, genuinely-105+-crossing stretch from the SAME session," and the
comment states plainly that "105 C only barely and rarely clears in this capture even in its
hottest (post-rollback) regime" — full derivation deferred by the comment itself to "a
separate report," which (same as the recovery section's rationale above) could not be located in this
tree.

**Retagged from a prior `[DEVICE-VERIFIED]` to `[UNVERIFIED]`.** This document's own glossary
requires a *locatable* capture for `[DEVICE-VERIFIED]`. Searched exhaustively by filename and by
content grep for both `RE-RUN-1315.tsv` and the sibling name `.re5-session-evidence.tsv` — neither
exists anywhere in this tree. Only two source files even mention the string `RE-RUN-1315`: this
document itself and `HardwareSessionSupervisorTest.kt`. Naming a file precisely is not the same as
possessing it. `[UNVERIFIED]` for the capture and the truncation-attributed sample values;
`[CODE-VERIFIED]` (not device verification) for the truncation-math internal consistency check
(the ten sample values are real numbers in the test file, and 841/8 = 105.125 is arithmetically
consistent with a mean-over-8 claim, but that is a self-consistency check on numbers in a test
file, not a device capture). `[OWED]` for the raw `.tsv` file itself, which was not located, and
for the "83.4/89.6/..." illustrative trace's own derivation, whose "full derivation" is deferred to
unavailable development notes.

## S7 — per-cluster thermal attribution (OWED)

Cross-referenced with S3.5's policy0-pinning claim above — both point at the same debt. Recovered
directly from `ThermalDerateLadder.kt:22-27`: "the design identifies the prime cluster
(`policy6`) as the hotspot, and whether `policy0` should ever join the ladder depends on OWED
per-cluster thermal attribution (spec S3.5/S7: which of `cpuss-0`/`cpuss-1` maps to which policy
is not yet established)." No code in this tree establishes that mapping — `[OWED]`, live debt,
surfaced prominently as an unresolved technical limit. This is the reason `policy0` is
pinned at nominal on every rung of every profile rather than being made part of the ladder.

## Sections cited but unrecoverable

The following section numbers are referenced somewhere in the codebase's citation trail but no
citing comment describes their content — only a bare pointer exists, or the pointer's content
maps entirely into a section already covered above with nothing left over. Listed honestly rather
than filled in:

- No section beyond S1, S2, S3.1-S3.5, S4 (including S4.3), S5 (tests 1/2/3/4/5/9), and S7 was
  cited with recoverable content anywhere this search covered. A prior pass of this document
  claimed nothing beyond S2/S3.1-S3.6/S4/S5/S7 was cited with content, and simultaneously denied
  S1 and S4.3 existed while discussing their content elsewhere in the same document (S1's 104.5 C
  figure under S2; S4.3's fan commits in the commit table) — that self-contradiction is corrected
  here by restoring S1 and S4.3 as their own sections. There is also no "S3.6" in this tree at
  all — the recovery content a prior pass filed under an invented "S3.6-ish" heading cites S3.4 in
  its own source comment; see the Recovery section above. If WAVE2 originally had further
  subsections (S6, S8, further S3.x beyond S3.1-S3.5, S5 tests other than 1/2/3/4/5/9), nothing in
  this tree describes them, and none are invented here.

Also explicitly not attempted: `ThermalDerateLadderTest.kt`'s own comment references "spec S5 test
8: 'no rung ever exceeds the qualified nominal, in any state'" as the property that
`ThermalDerateLadderTest` itself proves independently of the supervisor. That is an eighth S5 test
number, beyond the six the reconstruction covered — recorded here for completeness since it surfaced during
research, but not fully investigated as a numbered test entry above since it fell outside the
requested set (1,2,3,4,5,9).

Correction to a test-name citation: the ladder test cross-referenced against Performance's rungs
2-3 in the Live debts section below is not named "...performance rungs 2 and 3 match the TD
extrapolated table" — its real, full name is
`` `performance rungs 2 and 3 match the TD extrapolated table and rung 3 doubles as the floor` ``
(`ThermalDerateLadderTest.kt:119`).

## Live debts (surfaced, not resolved)

Four debts, one newly surfaced by gate-mandated re-verification of this document. These remain unresolved technical limits:

1. **[OWED] — WAVE2's thermal derate ladder appears not to actuate on the reference device.** A
   prior pass of this document stated as fact that Balanced's and quiet's TD-extrapolated ladders
   "are signed into the manifest." That claim is `[UNKNOWN]`, and is contradicted by the only
   signing artifact located in this tree:

   - `historical sequence-9 request (not distributed)` has header `MIMIR_QUALIFICATION_REQUEST_V2`
     and contains **no `rung=` blocks** for any of its three profiles (balanced, performance,
     quiet) — verified by reading the file directly.
   - The historical qualification-signing notes (not included here) define `MIMIR_QUALIFICATION_REQUEST_V3` as the format that carries
     `rung=` blocks per profile; those historical notes state plainly that "A `V3` request without any
     `rung=` blocks for a profile is legal — that profile simply carries no derate rungs," which by
     construction means a **V2** request carries no rungs for any profile at all — V2 has no
     `rung=` syntax to begin with.
   - `QualificationManifestGenerator.java:56-69` states, in its own comment, that the tool "MINTS a
     v2-shaped artifact every time it re-signs an unchanged `MIMIR_QUALIFICATION_REQUEST_V2`
     request file" and that this v2 threshold is *deliberately decoupled* from the current schema
     (3) specifically so V2 requests are **not** silently upgraded to schema-3 bytes — because
     reproducing the exact same schema-2 bytes for an unchanged (sequence, content) pair is
     required, or the on-device sequence store (binding one sequence to one payload hash) rejects
     the re-issued manifest as `REJECTED_CONFLICT`.
   - The generator separately rejects any rungs on a request below schema v3
     (`DERATE_RUNGS_SCHEMA_INVALID`, `QualificationManifestGenerator.java:369-373`).
   - `historical sequence-9 request (not distributed)` was committed 2026-08-07 (commit
     `61c506ff`, "Debug builds: one shared key, so a tester's device accepts any Mac's build") —
     i.e. **after** WAVE2 Stage B landed 2026-08-06 (`b02fed93`), so this is not a stale
     pre-Stage-B artifact; it is the current one.
   - **PROVEN by decoding the issued manifest** (2026-08-08, upgraded from "corroborating" after
     the binary was decoded against `QualificationManifestCodec.encodePayload`
     (`QualificationManifest.kt:351-362`), whose field order is: `PAYLOAD_MAGIC`, `VERSION`,
     `schemaVersion`, `keyId` (length-prefixed), `sequence` (long), `hardwareFingerprint`,
     `deviceProfile`, `servicePlanSchema`, `profileCount`). Decoding
     `[private issued-artifact path omitted]`
     yields:

         envelope MQMS version=1 payload_len=934 (file 1014 bytes)
         payload  MQMP version=1  schemaVersion=2  sequence=9
         deviceProfile=ayn-odin3-cq8725s-pserver
         hardwareFingerprint=[historical value omitted]
         servicePlanSchema=2  profileCount=3

     The decode is **self-validating**: `sequence=9`, `deviceProfile`, `hardwareFingerprint` and
     `profileCount=3` independently match `request.v2` and the known Odin 3 fingerprint, so the
     field alignment cannot be accidental. The earlier reading of `0000 0002` as "the third int
     field" was right by luck — that offset is `schemaVersion`, and the `0000 0040` that follows is
     the 4-byte length prefix of `keyId`, not the sequence.

     **`schemaVersion = 2`. Derate rungs require schema 3. The manifest installed on the reference
     device carries none.** This is no longer an inference from the request format; it is decoded
     from the signed artifact itself. No V3 request was available to the original author.

   **Runtime consequence, verified in code:** `MimirTweakerService.kt:1279-1288` — when
   `productQualificationResolver.qualifiedDerateRungPlan(...)` resolves to `null` for a rung (which
   it will, for any rung, for any of the three profiles, given the only request file in this tree
   carries none), the transaction returns `code = "THERMAL_DERATE_UNSIGNED_RUNG"`,
   `applied = false`, `restored = false`, with the message "Thermal derate rung $rung is not
   signed for profile $profileId; remaining at the current ceiling." The test
   `` `an unsigned derate rung degrades to remain and never falls back to restore` ``
   (`HardwareSessionSupervisorTest.kt:1557`) pins exactly this: `THERMAL_DERATE_UNSIGNED_RUNG`,
   zero restorer calls, session stays active.

   **In plain terms: Stage A (this document's S3.1-S3.5) decides a rung to move to; Stage B's
   apply (S4) fails closed on this device's current signing state and the session remains at
   whatever ceiling it already had — it does not derate further, and it does not restore.** The
   separate, independent mechanisms that still protect the device regardless of this gap are
   unaffected and unrelated: the fan full-duty intervention at 84 C
   (`FanThermalControl.kt:326`), the GPU witness override at 85 C (`FanThermalControl.kt:92`), and
   Guardian's own >95 C restore trip (`FanThermalControl.kt:67`). These are stated factually, not
   as reassurance that the derate gap doesn't matter — they are a different layer, engaged at
   different (higher, and independent of signing state) thresholds.

   **`[UNKNOWN]`, and the one thing that would overturn this finding:** whether a
   `MIMIR_QUALIFICATION_REQUEST_V3` request with populated `rung=` blocks exists somewhere outside
   this tree. The historical qualification-signing notes (not included here) stated that outputs
   were kept privately, so their
   absence from this tree is not itself proof none exists — the public source cannot establish this.

   The nominals (rung 0) remain on solid footing, unaffected by the above: Balanced's and quiet's
   *nominals* are `[DESIGN-CONSTRAINT]` (SEQ9 spec S1) and `[SIGNED]` — this is the one `[SIGNED]` tag in
   this document that survives gate re-verification, confirmed byte-for-byte against
   `historical sequence-9 request (not distributed)`: balanced `cpu.0=1996800, cpu.6=2438400,
   gpuMaxPowerLevel=2`; quiet `cpu.0=1785600, cpu.6=2246400, gpuMaxPowerLevel=5`; performance
   `cpu.0=1996800, cpu.6=3072000, gpuMaxPowerLevel=0` — all three match `ThermalDerateLadder.kt`'s
   `nominalPolicy6Khz`/`nominalGpuLevel`/`policy0MaxKhz` values exactly (re-confirmed against the
   file directly during this pass, all nine values).

   Performance rungs 2-3 and every rung of Balanced and quiet remain `[EXTRAPOLATED]` —
   not measured as optimal — per `ThermalDerateLadder.kt:41-44` and the matching tests
   (`` `performance rungs 2 and 3 match the TD extrapolated table and rung 3 doubles as the floor` ``,
   `` `balanced ladder matches the SEQ9 TD extrapolated table and every rung is extrapolated` ``, `` `quiet ladder matches the SEQ9 TD extrapolated table and every rung is extrapolated` ``). Only Performance's rung 1 carries an `[DESIGN-CONSTRAINT]` value for its ladder
   step itself, per `ThermalDerateLadder.kt:38-40`. Whether that value was ever actually signed
   into a request in this tree is covered by debt 4 below — as of this tree's only request file, it
   was not.

2. **Per-cluster thermal attribution is OWED** (S3.5/S7, above) — which of `cpuss-0`/`cpuss-1` maps
   to which CPU policy is not established anywhere in this tree, which is the stated reason
   `policy0` is pinned at nominal on every rung instead of joining the ladder.

3. **Ladder recovery does not exist** (see the Recovery section above) — `thermalRung` only ever
   increases within an active session; there is no code path that steps it back up after sustained
   cooling. The in-code claim that omitting recovery is "deliberate, not an oversight" points at a
   development record this search could not locate; the rationale is `[UNKNOWN]`.

4. **Performance rung 1's "verified on-device" claim is `[UNVERIFIED]`, not `[DEVICE-VERIFIED]`.**
   `ThermalDerateLadder.kt:38-40` asserts rung 1 is "specified exactly, both axes verified
   on-device 2026-08-06," and the test
   `` `performance rung 1 matches the recorded device readings exactly` ``
   (`ThermalDerateLadderTest.kt:107`) pins the values but not a capture. No locatable device
   capture backs the "verified on-device" claim itself in this tree — retagged from a prior
   `[DEVICE-VERIFIED]` accordingly. The rung-1 *values* remain `[DESIGN-CONSTRAINT]`, since that ruling
   (S3.5 above) is quoted in-tree; only the on-device-verification claim is downgraded.

## Cross-referenced commits (dates/provenance)

| Commit | Date | Subject |
|---|---|---|
| `1260eedc` | 2026-08-06 | WAVE 2 Stage A: thermal events derate instead of releasing to Stock |
| `b02fed93` | 2026-08-06 | WAVE 2 Stage B: actuate the derate by signing the ladder rungs (schema v3) |
| `de85c868` | 2026-08-06 | Sequence 9: retune Balanced and Efficiency to Performance's architecture |
| `95f6fa7b` | 2026-08-06 | Fan ownership via mode 5 (SPORT); fan drift stops costing the profile |
| `878ba063` | 2026-08-06 | Fan lane: close the composed-path Restore, wire the reasserter, extend the Guardian web |
| `61c506ff` | 2026-08-07 | Debug builds: one shared key, so a tester's device accepts any Mac's build (added `sequence-9/request.v2`, the schema-v2, no-rungs request this document's live debt 1 depends on) |

`95f6fa7b` and `878ba063` are fan-lane commits (S4.3's `RepairFanTarget`/fan-reassert mechanism,
now reconstructed as its own S4.3 section above) — real and dated correctly. Their content is a
sibling mechanism to thermal derate, not part of the thermal-derate ladder itself, but is no longer
excluded from this document's section coverage now that S4.3 has recoverable content of its own.

## Not covered by this document

`SEQ9_PROFILE_RETUNE_SPEC.md` and `WAVE1A_SPEC.md` are **also** missing from every git ref and from
disk, by the same search that failed to find this file. **Their citations were resolved 2026-08-09**
rather than reconstructed: the SEQ9 references carried a recorded technical requirement (2026-08-06) hung on a
phantom document, so the requirement stayed and the filename went, with the ladder table repointed at
`ThermalDerateLadder.PROFILES`, which is the authority rather than a copy of one. The WAVE1A
references were test-numbering lineage only; the numbers remain as history under an explicit header
in `HardwareSessionSupervisorTest` saying the document never existed. Neither is reconstructed here.
Do not treat this document as covering their content — where
a WAVE2 claim above depends on a SEQ9 or WAVE1A fact (e.g. the SEQ9 S1 nominals, or WAVE1A's
original consecutive-streak debounce), that fact is cited to the specific commit or code that
proves it, never asserted on the missing spec's authority alone.

## Historical rung-verification programme (2026-08-08)

The proposed programme was to verify thermal efficacy on hardware before signing a V3
sequence 10. The extrapolated table alone does not establish efficacy.

### Step 1 — structural admissibility: PASSED `[DEVICE-VERIFIED]`

`QualificationManifest.allowsProfiles` requires every rung frequency to exist in the device's own
`scaling_available_frequencies` and every GPU level to be a valid index, so a rung that fails this
would sink the entire bundle at validation time regardless of thermal behaviour. Read from Odin 3
`reference device` on 2026-08-08:

    policy0: 384000 556800 748800 960000 1152000 1363200 1555200 1785600 1996800 2227200
             2400000 2745600 2918400 3072000 3321600 3532800
    policy6: 1017600 1209600 1401600 1689600 1958400 2246400 2438400 2649600 2841600 3072000
             3283200 3513600 3801600 4089600 4204800 4320000
    GPU:     num_pwrlevels = 14 (indices 0..13; 0 = 1100 MHz fastest, 13 = 160 MHz slowest)

| profile | policy0 | rung 1 | rung 2 | rung 3 (floor) | verdict |
|---|---|---|---|---|---|
| performance | 1 996 800 ✓ | 2 841 600 ✓ / L2 ✓ | 2 649 600 ✓ / L2 ✓ | 2 438 400 ✓ / L3 ✓ | admissible |
| balanced | 1 996 800 ✓ | 2 246 400 ✓ / L3 ✓ | 1 958 400 ✓ / L3 ✓ | 1 958 400 ✓ / L4 ✓ | admissible |
| quiet | 1 785 600 ✓ | 1 958 400 ✓ / L5 ✓ | 1 958 400 ✓ / L6 ✓ | 1 689 600 ✓ / L6 ✓ | admissible |

**All 9 rungs across all 3 profiles are structurally admissible on this device.** Every policy6
frequency is in the table, both policy0 values are in the table, and every GPU level is inside
0..13. Note balanced rung 2 → rung 3 and quiet rung 1 → rung 2 hold policy6 flat and step only the
GPU, which is legal (the domination rules require non-increasing, not strictly decreasing).

This establishes admissibility ONLY. It says nothing about whether a rung actually sheds heat.

### Step 2 — thermal efficacy: NOT STARTED `[OWED]`

What remains unproven for the 8 rungs that are not performance rung 1: that applying the rung
measurably arrests a thermal rise under sustained load. Requires, per profile and per rung, a
load run with the clamp applied and temperature sampled, plus readback confirming the clamp
actually took at `scaling_max_freq` and the GPU level node.

Known obstacle to naming here rather than discovering later: **the normal apply path cannot be
used to test this**, because it is exactly the path that is broken — an unsigned rung returns
`THERMAL_DERATE_UNSIGNED_RUNG` and remains at the current ceiling. A harness must therefore either
drive the clamp through the privileged transport directly, or a provisional V3 manifest must be
signed for test purposes only and NOT shipped. Which of those is acceptable is `[UNKNOWN]` and is
a separate validation decision.

### Step 3 — sign V3 sequence 10: BLOCKED on step 2 `[OWED]`

Sequence 9 cannot be re-signed with new content: the on-device sequence store binds one sequence to
one payload hash and would reject the re-issue as `REJECTED_CONFLICT`
(`QualificationManifestGenerator.java:56-69`). Activation therefore requires a NEW sequence built
from a `MIMIR_QUALIFICATION_REQUEST_V3` request carrying `rung=` blocks.
