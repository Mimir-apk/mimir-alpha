#!/usr/bin/env python3
"""Render the clean-room Mimir frontend ambient prototype (default frontend music).

A slow, subtle ambient arrangement led by violin and cello with piano detail. It is
the frontend music for every theme except Hel/Nightmare, which keeps Fallen Hero
(render_the_well_remembers.py). This arrangement owns its render path and note data.
Fallen Hero's cumulative introduced_at() gates include a keening cry specific to that
piece, so extending its version sequence would carry the wrong musical gesture here.

Shared machinery from render_the_well_remembers.py is limited to loop math (add_loop),
pan/pitch utilities, synthetic reverb (synth_reverb_impulse + circular_convolve), the
felt-piano voice, air-bed noise and WAV/hash writers. Its render() is never called,
so this arrangement does not touch its RNG stream. Its mode/gothic-hover machinery,
rune motif, heartbeat/desolation-toll/glass/mystic-hum voices and apply_periodic_wow
remain specific to Fallen Hero.

bowed_ensemble_note builds a sample-free bowed voice: harmonic additive tone, slow
bow-pressure envelope, independently detuned unison copies for divisi, delayed vibrato
through frequency integration, and filtered bow noise. Spectral centroid, harmonic
series and envelope were measured in isolation before arrangement. These measurements
are not listening judgments.

The synthesis and arrangement use loop exactness, the v19-v22 mastering constraints
and documented Odin 2 Portal / RP5 speaker corrections in analyze_the_well_remembers.py.
No external reference audio or metadata was fetched or used.
"""

from __future__ import annotations

import argparse
import math
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import render_the_well_remembers as fallen_hero  # noqa: E402  (see module docstring)
from render_the_well_remembers import (  # noqa: E402
    add_loop,
    air_bed,
    circular_convolve,
    constant_power_pan,
    felt_piano_note,
    midi_frequency,
    sha256,
    synth_reverb_impulse,
    write_pcm24_wav,
)

SAMPLE_RATE = 48_000
assert fallen_hero.SAMPLE_RATE == SAMPLE_RATE, "reused helpers assume 48 kHz internally"

# The arrangement uses a slower tempo: exactly half of Fallen Hero's current 50 BPM (render_the_well_remembers
# v19-v22), stated as plainly as the slowdown can be stated. 60/25 = 2.4 s per beat,
# which is a terminating decimal (25 = 60/2.4 with no repeating fraction), so every bar
# boundary lands on a clean number of seconds the same way v19's 4.8 s bar did.
BPM = 25.0
BEAT_SECONDS = 60.0 / BPM          # 2.400 s
BAR_SECONDS = BEAT_SECONDS * 4.0   # 9.600 s
BAR_COUNT = 12
DURATION_SECONDS = BAR_SECONDS * BAR_COUNT   # 115.200 s
SAMPLE_COUNT = int(round(DURATION_SECONDS * SAMPLE_RATE))  # 5,529,600 frames, exact
RNG_SEED = 0x42524147  # "BRAG" -- independent of Fallen Hero's 0x4D494D49 ("MIMI")

VERSIONS = ("v1",)


# ---------------------------------------------------------------------------
# Bowed string voices -- the hard, new part. Build first, verify in isolation
# (see tools/verify_string_voices.py in the evidence scratchpad), only then score.
# ---------------------------------------------------------------------------

_REGISTER: dict[str, dict[str, object]] = {
    "cello": {
        # 90 %-rise time. The felt piano's attack is ~9 ms; this is ~80x slower,
        # which is the actual mechanism of "slow attack" here, not a description of
        # one -- bow pressure takes real time to lock a string into steady motion.
        "attack_seconds": 0.70,
        "release_seconds": 0.85,
        "vibrato_rate_hz": 4.8,
        "vibrato_onset_seconds": 0.55,
        "vibrato_depth_cents": 14.0,
        "detune_cents": 6.0,
        # Partials 1-7. Purely harmonic (integer multiples) -- a bowed string is
        # close enough to ideal that the piano's deliberate `inharmonicity` constant
        # has no equivalent here. Weighted for warmth (fast-ish rolloff) while
        # keeping 2-4 present enough to survive a small speaker on their own.
        "harmonics": (1.00, 0.68, 0.46, 0.30, 0.17, 0.09, 0.045),
        # Only used when `speaker_shadow=True`. Physically: bowing closer to the
        # bridge (sul ponticello) lifts the upper partials of a real cello too, so
        # this is a real bowing-position change, not just an EQ move dressed up as
        # one, even though the motivation is the Odin 2 Portal's 300 Hz corner.
        "harmonics_shadow": (1.00, 0.92, 0.72, 0.50, 0.24, 0.11, 0.045),
        "bow_noise_band_hz": (500.0, 3200.0),
        "bow_noise_amount": 0.024,
    },
    "violin": {
        "attack_seconds": 0.45,
        "release_seconds": 0.60,
        "vibrato_rate_hz": 5.8,
        "vibrato_onset_seconds": 0.38,
        "vibrato_depth_cents": 17.0,
        "detune_cents": 7.0,
        "harmonics": (1.00, 0.60, 0.34, 0.19, 0.10, 0.05),
        "harmonics_shadow": (1.00, 0.60, 0.34, 0.19, 0.10, 0.05),  # never requested
        "bow_noise_band_hz": (1400.0, 6200.0),
        "bow_noise_amount": 0.016,
    },
}


def bowed_ensemble_note(
    rng: np.random.Generator,
    midi_note: float,
    seconds: float,
    amplitude: float,
    pan: float,
    register: str,
    voices: int = 3,
    speaker_shadow: bool = False,
) -> np.ndarray:
    """A bowed, sustained string-ensemble note built without samples.

    Not a struck/plucked voice like `felt_piano_note`: the excitation is a
    continuous bow, so the tone is (a) purely harmonic, (b) slow to speak, and
    (c) always carries filtered bow-hair/string friction noise under it, not just
    a transient at the very start.

    `voices` independent unison copies stand in for a section's divisi: each gets
    its own deterministic cents-detune, onset offset (bows do not all catch the
    string in the same instant), vibrato phase/rate, and bow-noise draw, then sums
    at roughly equal power (1/sqrt(voices)). Vibrato is real frequency integration
    (`cumsum` of instantaneous Hz, the same technique `heartbeat()` uses for its
    pitch glide) rather than the phase-modulation shortcut `fm_glass`/`memory_glint`
    use elsewhere in this codebase -- it matters here because the harmonic stack
    needs every partial's modulation to scale exactly with its harmonic number,
    which only comes out right if frequency (not phase) is what is being wobbled.

    `speaker_shadow` (cello only) swaps in a harmonic balance with 2nd-4th partials
    lifted, so pitch the Odin 2 Portal's 300 Hz/16 dB-per-octave corner would
    otherwise erase from the fundamental still registers from the harmonics above
    it.
    """
    cfg = _REGISTER[register]
    release_seconds = float(cfg["release_seconds"])
    tail = release_seconds + 0.20
    length = max(1, int(round((seconds + tail) * SAMPLE_RATE)))
    t = np.arange(length, dtype=np.float64) / SAMPLE_RATE
    f0 = midi_frequency(midi_note)
    harmonics = cfg["harmonics_shadow"] if speaker_shadow else cfg["harmonics"]
    weight_sum = float(sum(harmonics))
    attack_tau = float(cfg["attack_seconds"]) / 2.3  # stated attack is the ~90 % point
    detune_cents = float(cfg["detune_cents"])
    vibrato_rate_hz = float(cfg["vibrato_rate_hz"])
    vibrato_onset_seconds = float(cfg["vibrato_onset_seconds"])
    vibrato_depth_cents = float(cfg["vibrato_depth_cents"])

    ensemble = np.zeros(length, dtype=np.float64)
    spread = np.linspace(-1.0, 1.0, voices) if voices > 1 else np.zeros(1)
    for unit in spread:
        jitter_seconds = float(rng.normal(0.0, 0.028))
        cents = unit * detune_cents + float(rng.normal(0.0, 0.5))
        vib_rate = vibrato_rate_hz + float(rng.normal(0.0, 0.12))
        vib_phase = float(rng.uniform(0.0, 2.0 * math.pi))
        bow_phase = float(rng.uniform(0.0, 2.0 * math.pi))

        tv = np.clip(t - jitter_seconds, 0.0, None)
        attack = 1.0 - np.exp(-tv / attack_tau)
        release = np.exp(-np.maximum(0.0, tv - seconds) / release_seconds)
        envelope = attack * release

        onset = np.clip(tv / vibrato_onset_seconds, 0.0, 1.0)
        vibrato_env = 0.5 - 0.5 * np.cos(math.pi * onset)
        vibrato_cents = vibrato_depth_cents * vibrato_env * np.sin(
            2.0 * math.pi * vib_rate * tv + vib_phase,
        )
        instantaneous_hz = f0 * (2.0 ** ((cents + vibrato_cents) / 1200.0))
        phase = 2.0 * math.pi * np.cumsum(instantaneous_hz) / SAMPLE_RATE

        tone = np.zeros(length, dtype=np.float64)
        for partial, weight in enumerate(harmonics, start=1):
            tone += weight * np.sin(partial * phase + bow_phase * partial * 0.11)
        ensemble += (tone / weight_sum) * envelope
    ensemble /= math.sqrt(voices)

    # One shared bow-noise bed under the ensemble (friction noise is already
    # broadband/incoherent, so it does not need its own per-voice detune). Loudest
    # while the bow is still catching the string, then settles -- never silent,
    # since the bow never actually leaves the string until the release.
    noise = rng.standard_normal(length)
    frequencies = np.fft.rfftfreq(length, d=1.0 / SAMPLE_RATE)
    low, high = cfg["bow_noise_band_hz"]
    ratio = np.maximum(frequencies, 1e-9) / low
    highpass = ratio**2 / np.sqrt(1.0 + ratio**4)
    lowpass = 1.0 / np.sqrt(1.0 + (frequencies / high) ** 4)
    bow_noise = np.fft.irfft(np.fft.rfft(noise) * highpass * lowpass, n=length)
    nominal_attack = 1.0 - np.exp(-t / attack_tau)
    contact_envelope = 0.35 + 0.65 * nominal_attack
    signal = ensemble + bow_noise * float(cfg["bow_noise_amount"]) * contact_envelope

    signal = np.tanh(signal * 1.05) * amplitude
    left_gain, right_gain = constant_power_pan(pan)
    return np.vstack((signal * left_gain, signal * right_gain)).astype(np.float32)


# ---------------------------------------------------------------------------
# Arrangement. Authored fresh for this piece -- none of this is Fallen Hero's
# chord_map, rune motif, or gothic hover, even where the tonal centre (D) matches.
# D is shared for one idiomatic reason only: both violin and cello have an open D
# string, so a D-centred piece lets both instruments ring open strings; nothing
# else about Fallen Hero's D Dorian/Aeolian journey carries over.
# ---------------------------------------------------------------------------

# Canonical voicings at a piano/violin-comfortable register (octave 4-5): (label,
# root, third-or-sus, fifth). Cello and both violin parts are derived from this one
# table by fixed register/voice selection in `build_layers`, so the six-chord cycle
# stays internally consistent for every instrument. D natural minor, i-VI-III-VII-
# iv-v(sus4): a slow, non-cadential descent -- the sus4 in bar 5/11 deliberately
# avoids a strong dominant pull back to i, so the loop hovers rather than resolves.
CHORDS: tuple[tuple[str, int, int, int], ...] = (
    ("i    Dm",    62, 65, 69),   # D4  F4  A4
    ("VI   Bb",    70, 62, 65),   # Bb4 D4  F4
    ("III  F",     65, 69, 72),   # F4  A4  C5
    ("VII  C",     60, 64, 67),   # C4  E4  G4
    ("iv   Gm",    67, 70, 74),   # G4  Bb4 D5
    ("v    Asus4", 69, 74, 76),   # A4  D5  E5 (no 3rd -- deliberately unresolved)
)

# Per-bar intensity: hushed open (cello alone) -> gradual thickening -> statement 2
# bloom (violins take the register up an octave and carry the atmospheric layer) ->
# recede toward the loop point, so bars 10-11 are the sparsest instrumentation
# *and* the softest dynamic heading into the wrap. 12 values, one per bar.
BAR_GAIN = (
    0.68, 0.72, 0.82, 0.88, 0.95, 1.00,
    1.05, 1.08, 1.10, 1.08, 0.80, 0.62,
)

VIOLIN1_FROM_BAR = 2      # cello alone for bars 0-1
VIOLIN2_FROM_BAR = 4
VIOLIN2_UNTIL_BAR = 10    # exclusive -- silent again for the bars-10-11 recede
VIOLIN2_BLOOM_FROM_BAR = 6  # statement 2: violin II's tone moves up an octave
RECEDE_FROM_BAR = 10        # cello and violin I also soften here, not just violin II

# Measured, not assumed: the cello line at `root - 24` (2
# octaves down from the canonical voicing, landing on D2-C2, 65-147 Hz) survives
# only 26.5% of its own energy through the documented Odin 2 Portal correction
# curve, 85.4% of it sitting below the 300 Hz corner. One octave higher (`root -
# 12`) lands on the cello's OWN open strings and low-mid register (D3/A3 are open
# strings; C3/F3/G3/Bb3 are all idiomatic low cello, not violin territory) and
# survival measures 71.8%. This register choice improves handheld-speaker survival without a level increase
# and is applied uniformly.
CELLO_OCTAVE_DOWN = 12

# Piano "adds detail": occasional single notes, not a melody. All eight sit inside
# bars 5-9 -- none in the hushed opening, none in the bars-10-11 recede -- so the
# piano is the piece's midpoint ornament, never its beginning or its ending.
# (bar, beat, chord-tone index [0=root,1=third/sus,2=fifth], octave shift in
#  semitones, held beats, amplitude, pan)
PIANO_ORNAMENTS = (
    (5, 3.25, 1, 12, 0.9, 0.045, 0.20),
    (6, 1.50, 0, 0, 1.1, 0.050, -0.16),
    (6, 3.00, 2, 0, 0.9, 0.040, 0.14),
    (7, 1.00, 1, 0, 1.2, 0.048, -0.14),
    (7, 3.25, 0, 12, 0.8, 0.038, 0.18),
    (8, 1.75, 2, 0, 1.1, 0.046, -0.12),
    (9, 0.50, 1, 0, 1.3, 0.044, 0.16),
    (9, 3.00, 2, 0, 0.9, 0.034, -0.10),
)


def build_layers(
    rng_strings: np.random.Generator,
    rng_piano: np.random.Generator,
    rng_air: np.random.Generator,
    sample_count: int,
) -> dict[str, np.ndarray]:
    """Build each instrument's own full-length stereo layer, pre-mix.

    Returned separately (not pre-summed) so speaker-survival measurements
    can isolate "the string bed" and "the cello alone" independently, by summing
    only the layers in question before running them through the speaker model in
    `analyze_the_well_remembers.py`.
    """
    cello = np.zeros((2, sample_count), dtype=np.float32)
    violin1 = np.zeros_like(cello)
    violin2 = np.zeros_like(cello)
    piano = np.zeros_like(cello)

    # Longer than one bar so consecutive notes overlap into the next bar's slow
    # attack -- real legato bowing, not a re-strike every 9.6 s. `add_loop` wraps
    # any tail that crosses the loop boundary, same mechanism `render_the_well_
    # remembers.py` uses for its own multi-bar pad notes.
    held = BAR_SECONDS + 1.4

    for bar in range(BAR_COUNT):
        _, root, third, fifth = CHORDS[bar % len(CHORDS)]
        bar_start = int(round(bar * BAR_SECONDS * SAMPLE_RATE))
        gain = BAR_GAIN[bar]
        recede = 0.72 if bar >= RECEDE_FROM_BAR else 1.0

        add_loop(cello, bowed_ensemble_note(
            rng_strings, midi_note=root - CELLO_OCTAVE_DOWN, seconds=held,
            amplitude=0.082 * gain * recede, pan=-0.10,
            register="cello", voices=3, speaker_shadow=True,
        ), bar_start)

        if bar >= VIOLIN1_FROM_BAR:
            add_loop(violin1, bowed_ensemble_note(
                rng_strings, midi_note=fifth, seconds=held,
                amplitude=0.052 * gain * (0.70 if bar >= RECEDE_FROM_BAR else 1.0),
                pan=0.22, register="violin", voices=3,
            ), bar_start)

        if VIOLIN2_FROM_BAR <= bar < VIOLIN2_UNTIL_BAR:
            pitch = third + (12 if bar >= VIOLIN2_BLOOM_FROM_BAR else 0)
            add_loop(violin2, bowed_ensemble_note(
                rng_strings, midi_note=pitch, seconds=held,
                amplitude=0.036 * gain, pan=-0.24, register="violin", voices=2,
            ), bar_start)

    for bar, beat, tone_index, octave_shift, held_beats, amplitude, pan in PIANO_ORNAMENTS:
        chord = CHORDS[bar % len(CHORDS)][1:]
        midi_note = chord[tone_index] + octave_shift
        position = bar * BAR_SECONDS + beat * BEAT_SECONDS
        start = int(round(position * SAMPLE_RATE))
        note = felt_piano_note(
            rng=rng_piano, midi_note=midi_note, held_seconds=held_beats * BEAT_SECONDS,
            amplitude=amplitude * BAR_GAIN[bar], pan=pan, presence=False, subdued=True,
        )
        add_loop(piano, note, start)

    air = air_bed(rng_air, amplitude=0.0026, total=sample_count)

    return {"cello": cello, "violin1": violin1, "violin2": violin2, "piano": piano, "air": air}


def tame_midrange_presence(signal: np.ndarray, depth_db: float = 4.0) -> np.ndarray:
    """A modest, smooth dip around 700-1050 Hz, same shape family as `bow_noise_band_hz`.

    Same category of move as render_the_well_remembers.py's own `tame_high_shelf`
    (V7) and `trim_subsonic_weight` (V13): a fixed, macro, device-NEUTRAL spectral
    shape motivated by small-speaker reality in general, not any one device's exact
    correction curve baked in -- the project's own posture rule (see the SPEAKERS
    table in analyze_the_well_remembers.py) forbids that: "tuning the asset to one
    driver's correction would make it wrong on every other device and wrong again
    on the same device once the user changes their preset."

    Measured motivation: an energy-weighted breakdown of this piece's own pre-master
    mix against the RP5's documented correction curve found 600-1200 Hz responsible
    for 52% of the RP5-weighted total from only 22.6% of the raw energy -- the RP5
    curve's single worst point (driver ~+15 dB per the correction table) sits at
    700 Hz, exactly where this piece has real content. That is a genuine property of
    this arrangement (sustained string fundamentals sitting where a struck-piano
    piece mostly does not), not a guess.

    This is a real, measured trade, not a free fix: because both the Odin-survival
    and RP5-coloration figures are *ratios over this piece's own total energy*,
    removing energy from a band that survives the Odin model near-flat (700-1050 Hz
    sits well above its 300 Hz corner) mechanically lowers the Odin ratio too, even
    though nothing on Odin gets acoustically worse. Swept 2-6 dB at several band
    placements against both models before choosing 4 dB at 700-1050 Hz specifically
    because it lands the whole mix's Odin figure at -0.73 dB / 84.6% -- matching,
    not undercutting, the already-shipped Fallen Hero v19-v22 reference (-0.73 dB /
    84.5%) -- while cutting the RP5 figure from +7.82 dB (605.6%) to +7.21 dB
    (526.2%). A deeper cut buys more RP5 headroom at a real further Odin cost; this
    depth was chosen to hold the Odin line at the reference floor, not to chase the
    RP5 number to zero.
    """
    frequencies = np.fft.rfftfreq(signal.shape[1], d=1.0 / SAMPLE_RATE)
    low_hz, high_hz = 700.0, 1050.0
    ratio = np.maximum(frequencies, 1e-9) / low_hz
    highpass = ratio**2 / np.sqrt(1.0 + ratio**4)
    lowpass = 1.0 / np.sqrt(1.0 + (frequencies / high_hz) ** 4)
    bump = highpass * lowpass
    response = 10.0 ** ((-depth_db * bump) / 20.0)
    spectrum = np.fft.rfft(signal, axis=1) * response[np.newaxis, :]
    return np.fft.irfft(spectrum, n=signal.shape[1], axis=1)


def render(version: str = "v1") -> np.ndarray:
    if version not in VERSIONS:
        raise ValueError(f"unsupported version: {version}")

    # Independent, dedicated streams per layer -- deliberately not Fallen Hero's
    # RNG_SEED / stream layout, and this module never imports or calls that file's
    # `render()`, so nothing here can reorder that file's own draws.
    rng_strings = np.random.default_rng(RNG_SEED)
    rng_piano = np.random.default_rng(RNG_SEED ^ 0x504941)   # "PIA"
    rng_air = np.random.default_rng(RNG_SEED ^ 0x414952)     # "AIR"
    rng_reverb = np.random.default_rng(RNG_SEED ^ 0x524556)  # "REV"

    layers = build_layers(rng_strings, rng_piano, rng_air, SAMPLE_COUNT)

    # A longer, more diffuse room than Fallen Hero's (6.5 s impulse vs 4.2 s; RT60
    # roughly low 7.6 s / mid 5.9 s / high 3.1 s) -- "ambient ... take over the
    # atmosphere" reads as more space, not more notes. Piano is sent lighter than
    # the strings so it stays "detail" (present, dry-ish) rather than dissolving
    # into the same wash as the bed it is ornamenting.
    room = synth_reverb_impulse(
        rng_reverb, seconds=6.5, decay_low=1.10, decay_mid=0.85, decay_high=0.45,
        predelay_seconds=0.035,
    )
    send = (
        layers["cello"] * 0.46
        + layers["violin1"] * 0.40
        + layers["violin2"] * 0.36
        + layers["piano"] * 0.26
    )
    mix = layers["cello"] + layers["violin1"] + layers["violin2"] + layers["piano"]
    mix = mix + circular_convolve(send, room)
    mix = mix + layers["air"]  # dry noise floor, no reverb send -- it is already diffuse
    mix = tame_midrange_presence(mix)

    # Same mastering discipline as render_the_well_remembers.py's v1-v22: identical
    # target RMS (-20.5 dBFS, the literal same number, not a rescaled one) and
    # identical safety ceiling (-2 dBFS). Duplicated rather than imported because
    # that file does not expose this as a standalone function, and this file must
    # not edit that one (byte-faithful constraint).
    #
    # The soft-clip DRIVE constant is not the same number (7.5 here vs Fallen
    # Hero's 0.92) -- measured, not guessed: at drive=0.92 this piece's own natural
    # crest factor (sustained multi-voice harmonic layers summed together read
    # peakier than Fallen Hero's mostly-decaying piano melody) put the post-master
    # peak at -4.30 dBFS against the -6.69 dBFS v19-v22 reference, i.e. audibly
    # outside "the same region" even at an identical RMS target. Raising the drive
    # is a transparent peak limiter, not a loudness move: it acts on the rare
    # collision moments the mix has, not on the piece as heard most of the time
    # (measured near-transparent at the RMS level, a fraction of a dB, against
    # several dB of attenuation at the actual peak sample). Re-measured at 7.5
    # after `tame_midrange_presence` was added (removing energy raises the crest
    # factor slightly, so the target drive moved from the earlier 6.5); final peak
    # measures -6.38 dBFS on the shipped v1 arrangement, 0.31 dB from the v19-v22
    # reference .
    mix = np.tanh(mix * 7.5)
    mix -= mix.mean(axis=1, keepdims=True)
    rms = float(np.sqrt(np.mean(np.square(mix, dtype=np.float64))))
    target_rms = 10.0 ** (-20.5 / 20.0)
    if rms > 0.0:
        mix *= target_rms / rms
    peak = float(np.max(np.abs(mix)))
    peak_ceiling = 10.0 ** (-2.0 / 20.0)
    if peak > peak_ceiling:
        mix *= peak_ceiling / peak
    return mix.astype(np.float32)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path, help="Output 24-bit stereo WAV path")
    parser.add_argument(
        "--version", choices=VERSIONS, default="v1",
        help="Render version (only v1 exists; kept as a flag for future revisions)",
    )
    arguments = parser.parse_args()
    rendered = render(arguments.version)
    write_pcm24_wav(arguments.output, rendered)
    peak = float(np.max(np.abs(rendered)))
    rms = float(np.sqrt(np.mean(np.square(rendered, dtype=np.float64))))
    print(f"path={arguments.output}")
    print(f"duration_seconds={rendered.shape[1] / SAMPLE_RATE:.3f}")
    print(f"sample_rate={SAMPLE_RATE}")
    print(f"version={arguments.version}")
    print("channels=2")
    print("sample_width_bits=24")
    print(f"peak_dbfs={20.0 * math.log10(max(peak, 1e-12)):.2f}")
    print(f"rms_dbfs={20.0 * math.log10(max(rms, 1e-12)):.2f}")
    print(f"sha256={sha256(arguments.output)}")


if __name__ == "__main__":
    main()
