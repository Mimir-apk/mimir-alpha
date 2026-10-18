#!/usr/bin/env python3
"""Render the clean-room Mimir frontend-music prototype "The Well Remembers".

The renderer is deliberately sample-free. Every audible source is synthesized from
original note data, deterministic noise, and elementary oscillators in this file.
It writes a 60-second, 48 kHz, stereo PCM WAV intended for listening review,
not for production integration.
"""

from __future__ import annotations

import argparse
import hashlib
import math
import wave
from pathlib import Path

import numpy as np


SAMPLE_RATE = 48_000
BPM = 64.0
BEAT_SECONDS = 60.0 / BPM
BAR_SECONDS = BEAT_SECONDS * 4.0
BAR_COUNT = 16
DURATION_SECONDS = BAR_SECONDS * BAR_COUNT
SAMPLE_COUNT = int(round(DURATION_SECONDS * SAMPLE_RATE))
RNG_SEED = 0x4D494D49  # "MIMI"


ARRANGEMENTS = (
    "v1", "v2", "v3", "v4", "v5", "v6", "v7", "v8", "v9", "v10", "v11", "v12",
    "v13", "v14", "v15", "v16", "v17", "v18", "v19", "v20", "v21", "v22",
)


def introduced_at(version: str, arrangement: str) -> bool:
    """True when `arrangement` is `version` or any later one.

    Cumulative feature gates MUST use this rather than a literal set of version
    strings. Hand-written sets silently dropped a layer every time an arrangement was
    added and one set was missed: V5-V9 lost both the V2 analog-memory bed and the V3
    low-resonance pulses that way, and nothing failed — the layers simply stopped being
    rendered. Only a feature that is deliberately *retired* later (V2's glint
    constellation, which V3 replaced) should test an explicit set.
    """
    return ARRANGEMENTS.index(arrangement) >= ARRANGEMENTS.index(version)


def midi_frequency(note: int) -> float:
    return 440.0 * (2.0 ** ((note - 69) / 12.0))


def constant_power_pan(pan: float) -> tuple[float, float]:
    angle = (max(-1.0, min(1.0, pan)) + 1.0) * math.pi / 4.0
    return math.cos(angle), math.sin(angle)


def add_loop(destination: np.ndarray, source: np.ndarray, start: int) -> None:
    """Add source into a periodic destination, wrapping tails across the loop seam."""
    if source.size == 0:
        return
    start %= destination.shape[1]
    first = min(source.shape[1], destination.shape[1] - start)
    destination[:, start : start + first] += source[:, :first]
    remaining = source.shape[1] - first
    if remaining > 0:
        # No rendered event exceeds one complete loop.
        destination[:, :remaining] += source[:, first:]


def felt_piano_note(
    rng: np.random.Generator,
    midi_note: int,
    held_seconds: float,
    amplitude: float,
    pan: float,
    presence: bool = False,
    subdued: bool = False,
) -> np.ndarray:
    """A muted, slightly unstable modal-resonator piano built without samples.

    `presence` (V4 only) unmuffles the instrument's *own* upper partials and felt
    transient rather than adding any new voice. V1-V3 keep the original blanket.
    """
    tail_seconds = 3.8
    length = max(1, int(round((held_seconds + tail_seconds) * SAMPLE_RATE)))
    t = np.arange(length, dtype=np.float64) / SAMPLE_RATE
    frequency = midi_frequency(midi_note)
    attack = 1.0 - np.exp(-t / 0.009)
    body_decay = 1.45 + max(0.0, (60 - midi_note) * 0.018)
    release = np.exp(-np.maximum(0.0, t - held_seconds) * 1.75)
    body_envelope = attack * np.exp(-t / body_decay) * release

    # Slight inharmonicity plus a quiet detuned companion supplies felted-piano width.
    harmonics = np.zeros(length, dtype=np.float64)
    # V4 keeps the same six-partial series and the same fundamental balance; only the
    # rolloff into partials 3-6 is relaxed, which is where a handheld driver lives.
    if subdued:
        # Partials 2-4 stay lifted (that is the 1-2 kHz body a handheld can radiate);
        # partials 5-6 drop back below even the V1 values, because 3-5 kHz is where the
        # ear is most sensitive and where "too bright" actually lives.
        weights = (1.0, 0.50, 0.27, 0.135, 0.042, 0.015)
        partial_damping = 0.072
    elif presence:
        weights = (1.0, 0.50, 0.29, 0.175, 0.105, 0.062)
        partial_damping = 0.055
    else:
        weights = (1.0, 0.43, 0.20, 0.105, 0.052, 0.025)
        partial_damping = 0.085
    inharmonicity = 0.00022
    phase_seed = (midi_note * 0.271828) % (2.0 * math.pi)
    for index, weight in enumerate(weights, start=1):
        partial = frequency * index * math.sqrt(1.0 + inharmonicity * index * index)
        partial_decay = np.exp(-t * (0.10 + index * partial_damping))
        harmonics += weight * partial_decay * np.sin(
            2.0 * math.pi * partial * t + phase_seed * index,
        )

    detuned = 0.10 * np.sin(2.0 * math.pi * frequency * 1.00115 * t + 0.63)
    signal = (harmonics + detuned) * body_envelope

    # A soft deterministic hammer/felt transient; smoothed noise avoids vinyl-like crackle.
    # The V1-V3 28-sample boxcar nulls at ~1.7 kHz, filtering the felt out of exactly the
    # band a handheld speaker radiates best. V4 opens it to ~4.4 kHz and trims the level
    # so the texture returns without becoming a click.
    attack_length = min(length, int(0.075 * SAMPLE_RATE))
    noise = rng.standard_normal(attack_length)
    kernel_width = 17 if subdued else (11 if presence else 28)
    kernel = np.ones(kernel_width, dtype=np.float64) / kernel_width
    noise = np.convolve(noise, kernel, mode="same")
    noise *= np.exp(-np.arange(attack_length) / (0.018 * SAMPLE_RATE))
    signal[:attack_length] += noise * (0.088 if subdued else (0.105 if presence else 0.12))
    signal = np.tanh(signal * 1.18) * amplitude

    left_gain, right_gain = constant_power_pan(pan)
    stereo = np.vstack((signal * left_gain, signal * right_gain))
    return stereo.astype(np.float32)


def synth_pad_note(
    midi_note: int,
    seconds: float,
    amplitude: float,
    phase: float,
) -> np.ndarray:
    length = max(1, int(round(seconds * SAMPLE_RATE)))
    t = np.arange(length, dtype=np.float64) / SAMPLE_RATE
    frequency = midi_frequency(midi_note)
    edge = min(0.55, seconds / 3.0)
    attack = np.clip(t / max(edge, 0.001), 0.0, 1.0)
    release = np.clip((seconds - t) / max(edge, 0.001), 0.0, 1.0)
    envelope = np.minimum(attack, release)
    spectral_drift = 0.91 + 0.09 * np.sin(2.0 * math.pi * 0.031 * t + phase)
    sine = np.sin(2.0 * math.pi * frequency * t + phase)
    triangle = (2.0 / math.pi) * np.arcsin(
        np.sin(2.0 * math.pi * frequency * 0.5003 * t + phase * 0.7),
    )
    signal = (0.82 * sine + 0.18 * triangle) * envelope * spectral_drift * amplitude
    return signal.astype(np.float32)


def warm_memory_note(
    midi_note: int,
    seconds: float,
    amplitude: float,
    phase: float,
    pan: float,
) -> np.ndarray:
    """A slow, warm analog-memory voice made from authored oscillators only."""
    length = max(1, int(round(seconds * SAMPLE_RATE)))
    t = np.arange(length, dtype=np.float64) / SAMPLE_RATE
    frequency = midi_frequency(midi_note)
    edge = min(1.35, seconds / 3.2)
    attack = np.sin(np.clip(t / max(edge, 0.001), 0.0, 1.0) * math.pi / 2.0) ** 2
    release = np.sin(
        np.clip((seconds - t) / max(edge, 0.001), 0.0, 1.0) * math.pi / 2.0,
    ) ** 2
    envelope = np.minimum(attack, release)

    # A deliberately restrained three-oscillator ensemble. The partial balance is
    # warmer and less glassy than the existing pad, while remaining sample-free.
    signal = np.zeros(length, dtype=np.float64)
    for detune, weight, phase_offset in (
        (0.9979, 0.32, -0.31),
        (1.0000, 0.46, 0.00),
        (1.0026, 0.30, 0.37),
    ):
        theta = 2.0 * math.pi * frequency * detune * t + phase + phase_offset
        signal += weight * (
            np.sin(theta)
            + 0.22 * np.sin(2.0 * theta + 0.19)
            + 0.075 * np.sin(3.0 * theta - 0.27)
        )
    drift = 0.91 + 0.09 * np.sin(2.0 * math.pi * 0.043 * t + phase * 0.63)
    signal = np.tanh(signal * 0.88) * envelope * drift * amplitude
    left_gain, right_gain = constant_power_pan(pan)
    return np.vstack((signal * left_gain, signal * right_gain)).astype(np.float32)


def memory_glint(midi_note: int, amplitude: float, pan: float) -> np.ndarray:
    """A soft cybernetic pulse that blooms instead of striking like a bell."""
    seconds = 3.15
    length = int(seconds * SAMPLE_RATE)
    t = np.arange(length, dtype=np.float64) / SAMPLE_RATE
    carrier = midi_frequency(midi_note)
    modulator = carrier * 1.501
    modulation = 0.62 * np.exp(-t / 0.92)
    signal = np.sin(
        2.0 * math.pi * carrier * t
        + modulation * np.sin(2.0 * math.pi * modulator * t + 0.41),
    )
    envelope = (1.0 - np.exp(-t / 0.085)) * np.exp(-t / 1.18)
    signal *= envelope * amplitude
    left_gain, right_gain = constant_power_pan(pan)
    return np.vstack((signal * left_gain, signal * right_gain)).astype(np.float32)


def low_resonance_pulse(
    midi_note: int,
    amplitude: float,
    pan: float,
    speaker_harmonics: bool = False,
) -> np.ndarray:
    """A lower, faster-moving pulse with a long diffuse body instead of a bright tip.

    `speaker_harmonics` (V4 only) adds quiet 3rd and 5th partials to V3's lower notes
    and atmospheric resonance. A handheld driver radiates almost nothing at those
    fundamentals (MIDI 48-60 is 131-262 Hz). Supplying harmonics lets the ear reconstruct
    the missing fundamental and perceive the low pitch on that hardware. The fundamental
    itself is untouched, so headphones still hear V3's low end intact.
    """
    seconds = 4.65
    length = int(seconds * SAMPLE_RATE)
    t = np.arange(length, dtype=np.float64) / SAMPLE_RATE
    frequency = midi_frequency(midi_note)
    phase = 2.0 * math.pi * frequency * t
    slow_drift = 0.24 * np.sin(2.0 * math.pi * 0.19 * t + 0.37)
    signal = (
        0.76 * np.sin(phase + slow_drift)
        + 0.17 * np.sin(phase * 0.5 - 0.21)
        + 0.11 * np.sin(phase * 1.498 + 0.33)
    )
    if speaker_harmonics:
        # These decay faster than the body, so they read as part of the pulse's own
        # attack rather than as a new sustained upper line — which is exactly what
        # V3 retired when it dropped V2's glint constellation.
        harmonic_decay = np.exp(-t / 0.86)
        signal += 0.085 * harmonic_decay * np.sin(phase * 3.0 + 0.11)
        signal += 0.042 * harmonic_decay * np.sin(phase * 5.0 - 0.29)
    envelope = (1.0 - np.exp(-t / 0.105)) * np.exp(-t / 1.72)
    signal = np.tanh(signal * 0.92) * envelope * amplitude
    left_gain, right_gain = constant_power_pan(pan)
    return np.vstack((signal * left_gain, signal * right_gain)).astype(np.float32)


def heartbeat(rng: np.random.Generator) -> np.ndarray:
    seconds = 0.31
    length = int(seconds * SAMPLE_RATE)
    t = np.arange(length, dtype=np.float64) / SAMPLE_RATE
    pitch = 62.0 - 10.0 * np.clip(t / seconds, 0.0, 1.0)
    phase = 2.0 * math.pi * np.cumsum(pitch) / SAMPLE_RATE
    sub = np.sin(phase) * np.exp(-t / 0.105)
    click_noise = rng.standard_normal(length)
    click_noise = np.concatenate(([0.0], np.diff(click_noise)))
    click = click_noise * np.exp(-t / 0.010) * 0.055
    signal = np.tanh((sub * 0.18 + click) * 1.2)
    return np.vstack((signal, signal)).astype(np.float32)


def fm_glass(midi_note: int, amplitude: float, pan: float, soften: bool = False) -> np.ndarray:
    """Sparse synthetic glass.

    `soften` (V5 only) pulls the modulation index down and slows the attack. A 4 ms
    attack with an index of 2.1 is the classic two-operator FM bell — the sound most
    listeners read as "video game", which is precisely the cartoonish quality being
    designed out. Lower index and a slower attack leave a breathed tone instead.
    """
    seconds = 2.6
    length = int(seconds * SAMPLE_RATE)
    t = np.arange(length, dtype=np.float64) / SAMPLE_RATE
    carrier = midi_frequency(midi_note)
    modulator = carrier * 2.003
    index = (0.85 if soften else 2.1) * np.exp(-t / 0.44)
    signal = np.sin(2.0 * math.pi * carrier * t + index * np.sin(2.0 * math.pi * modulator * t))
    attack_tau = 0.030 if soften else 0.004
    signal *= (1.0 - np.exp(-t / attack_tau)) * np.exp(-t / 0.82) * amplitude
    left_gain, right_gain = constant_power_pan(pan)
    return np.vstack((signal * left_gain, signal * right_gain)).astype(np.float32)


def synth_reverb_impulse(
    rng: np.random.Generator,
    seconds: float,
    decay_low: float,
    decay_mid: float,
    decay_high: float,
    predelay_seconds: float,
) -> np.ndarray:
    """A diffuse synthetic room, generated here from seeded noise.

    This is **not** a sampled or downloaded impulse response — nothing is read from
    disk or the network. It is deterministic generated noise (the same clean-room
    category the felt-piano hammer already uses), shaped into three frequency bands
    that decay at different rates, which is what an actual room does: highs die first,
    lows ring on.

    V1-V4 had no reverb at all. Their `circular_delay_mix` is six discrete full-band
    echoes derived from a mono sum, which reads as slapback and comb colouration
    rather than space — a large part of why the result sounds synthetic.
    """
    length = max(1, int(round(seconds * SAMPLE_RATE)))
    t = np.arange(length, dtype=np.float64) / SAMPLE_RATE

    # Independent noise per channel so the tail is decorrelated and genuinely wide.
    noise = rng.standard_normal((2, length))
    frequencies = np.fft.rfftfreq(length, d=1.0 / SAMPLE_RATE)

    # Smooth crossovers at 300 Hz and 2.5 kHz; the three masks sum to unity.
    low_mask = 1.0 / (1.0 + (frequencies / 300.0) ** 2)
    high_mask = 1.0 / (1.0 + (2500.0 / np.maximum(frequencies, 1e-9)) ** 2)
    mid_mask = np.clip(1.0 - low_mask - high_mask, 0.0, 1.0)

    impulse = np.zeros((2, length), dtype=np.float64)
    for mask, decay in ((low_mask, decay_low), (mid_mask, decay_mid), (high_mask, decay_high)):
        band = np.fft.irfft(np.fft.rfft(noise, axis=1) * mask[np.newaxis, :], n=length, axis=1)
        impulse += band * np.exp(-t / decay)[np.newaxis, :]

    # Real rooms take a few milliseconds to reach full echo density, and the direct
    # sound arrives before the walls answer.
    impulse *= (1.0 - np.exp(-t / 0.018))[np.newaxis, :]

    # Guard the truncation. An impulse that is still audible when the buffer ends acts
    # as a gated reverb — the tail stops dead rather than decaying away. The decay
    # constants below are chosen so this window has almost nothing left to do, but it
    # keeps the artifact impossible rather than merely unlikely.
    fade = np.clip((seconds - t) / 0.25, 0.0, 1.0) ** 2
    impulse *= fade[np.newaxis, :]

    predelay = int(round(predelay_seconds * SAMPLE_RATE))
    if predelay > 0:
        impulse = np.concatenate(
            (np.zeros((2, predelay)), impulse[:, : length - predelay]), axis=1,
        )

    energy = float(np.sqrt(np.sum(np.square(impulse))))
    return impulse / energy if energy > 0.0 else impulse


def circular_convolve(signal: np.ndarray, impulse: np.ndarray) -> np.ndarray:
    """Convolve one loop period with an impulse, wrapping the tail across the seam.

    Circular convolution is exactly what a seamless loop needs: the reverb tail of the
    last bar lands back on the first, so the seam stays sample-accurate for free.
    """
    length = signal.shape[1]
    padded = np.zeros((2, length), dtype=np.float64)
    usable = min(impulse.shape[1], length)
    padded[:, :usable] = impulse[:, :usable]

    result = np.empty((2, length), dtype=np.float64)
    for channel in range(2):
        result[channel] = np.fft.irfft(
            np.fft.rfft(signal[channel]) * np.fft.rfft(padded[channel]), n=length,
        )
    return result


def air_bed(
    rng: np.random.Generator,
    amplitude: float,
    total: int = SAMPLE_COUNT,
) -> np.ndarray:
    """A very quiet tilted-noise room tone, exactly one loop long.

    V1-V4 have no noise floor whatsoever, so the space between notes is digitally
    black — one of the strongest tells that a recording was synthesized. This sits far
    below the music and is generated, not sampled.
    """
    noise = rng.standard_normal((2, total))
    frequencies = np.fft.rfftfreq(total, d=1.0 / SAMPLE_RATE)
    # Pink-ish tilt with the top rolled off: air, not hiss.
    tilt = 1.0 / np.sqrt(np.maximum(frequencies, 20.0))
    tilt *= 1.0 / (1.0 + (frequencies / 6000.0) ** 2)
    shaped = np.fft.irfft(np.fft.rfft(noise, axis=1) * tilt[np.newaxis, :], n=total, axis=1)
    peak = float(np.max(np.abs(shaped)))
    return (shaped / peak * amplitude) if peak > 0.0 else shaped


def breathing_noise_bed(
    rng: np.random.Generator,
    amplitude: float,
    gentle: bool = False,
    delay_bars: float = 0.0,
    sailing: bool = False,
    cycles: float = 5.0,
    total: int = SAMPLE_COUNT,
    duration: float = DURATION_SECONDS,
) -> np.ndarray:
    """An unpitched filtered-air layer that swells and subsides with the bar.

    the rhythmic background element should be a breathing
    noise texture — a slow tide rather than a note or a drum. Built from the same
    deterministic generated noise as the reverb and the air bed; nothing is sampled.

    One swell per bar at 64 BPM is 16 per minute, which is literally a resting
    respiration rate, and 16 whole cycles across the 60 s loop, so the texture is
    loop-periodic by construction. The spectrum opens upward as the swell rises — air
    moving, not a volume fader on static noise — and the bands it opens into
    (620 Hz-5.2 kHz) are the ones a handheld driver actually radiates.
    """
    noise = rng.standard_normal((2, total))
    frequencies = np.fft.rfftfreq(total, d=1.0 / SAMPLE_RATE)
    spectrum = np.fft.rfft(noise, axis=1)

    def band(low_hz: float, high_hz: float) -> np.ndarray:
        ratio = np.maximum(frequencies, 1e-9) / low_hz
        highpass = ratio**2 / np.sqrt(1.0 + ratio**4)
        lowpass = 1.0 / np.sqrt(1.0 + (frequencies / high_hz) ** 4)
        mask = highpass * lowpass
        return np.fft.irfft(spectrum * mask[np.newaxis, :], n=total, axis=1)

    low, mid, high = band(180.0, 620.0), band(620.0, 1900.0), band(1900.0, 5200.0)

    seconds = np.arange(total, dtype=np.float64) / SAMPLE_RATE
    # the swell The swell waits between phrases. A whole number of
    # bars of offset would be invisible on a loop, so the delay is a fraction of one
    # bar — it moves the crest away from the downbeat the piano is already occupying.
    bar_phase = (
        2.0 * math.pi * BAR_COUNT * seconds / duration
        - 2.0 * math.pi * delay_bars
    )

    # Exponent > 1 flattens the trough but steepens the approach to the crest, which is
    # precisely the "splash". `gentle` returns it to a plain raised cosine.
    if sailing:
        #  One swell per bar gave no gap at all. This runs five
        # waves across the loop — one every 12 s, which is roughly the period of real
        # swell at a shore — and each occupies only `duty` of its cycle, so what
        # follows the fall is open water rather than the next rise.
        cycle = (cycles * seconds / duration + delay_bars) % 1.0
        duty, skew = 0.58, 0.62
        span = np.clip(cycle / duty, 0.0, 1.0)
        rise = np.clip(span / skew, 0.0, 1.0)
        fall = np.clip((1.0 - span) / (1.0 - skew), 0.0, 1.0)
        # Rises over 62% of the active span and falls over the rest: a swell leans in
        # slowly and lets go a little faster. Zero at both ends, so the lull is silent.
        breath = 0.5 - 0.5 * np.cos(math.pi * np.minimum(rise, fall))
        # Two whole variation cycles across the loop, so no two waves match and the
        # seam is still exact.
        breath = breath * (
            0.72 + 0.28 * (
                0.5 - 0.5 * np.cos(2.0 * math.pi * 2.0 * seconds / duration + 0.70)
            )
        )
    else:
        breath = (0.5 - 0.5 * np.cos(bar_phase)) ** (1.0 if gentle else 1.6)
    # A half-bar articulation gives the tide some rhythmic definition — but at the old
    # depth it lands as a second, faster hit inside every swell.
    if not sailing:
        breath *= (0.93 if gentle else 0.82) + (0.07 if gentle else 0.18) * (
            0.5 - 0.5 * np.cos(2.0 * bar_phase + 0.40)
        )
        # ...and a four-bar drift stops consecutive bars from breathing identically.
        # Four whole cycles across the loop, so the seam is still exact.
        breath *= 0.76 + 0.24 * (
            0.5 - 0.5 * np.cos(2.0 * math.pi * 4.0 * seconds / duration + 0.70)
        )

    if gentle or sailing:
        # Low-pass the envelope itself against a raised-cosine window, circularly so the
        # loop stays exact. This makes an abrupt onset arithmetically impossible rather
        # than merely unlikely — nothing in the contour can rise faster than the window.
        span = int(0.40 * SAMPLE_RATE)
        window = np.zeros(total, dtype=np.float64)
        window[:span] = np.hanning(span)
        window /= window.sum()
        breath = np.fft.irfft(
            np.fft.rfft(breath) * np.fft.rfft(window), n=total,
        )
        breath = np.roll(breath, -span // 2)
        breath = np.clip(breath, 0.0, None)

    # The brightest band arriving on a squared curve is the other half of the splash:
    # the tide gets suddenly bright at the crest. A gentler exponent lets it bloom.
    high_curve = breath ** (1.25 if (gentle or sailing) else 2.0)
    opened = low + mid * breath[np.newaxis, :] + high * high_curve[np.newaxis, :]
    # A whisper of air never quite leaves, so the texture recedes instead of switching off.
    signal = opened * (0.15 + 0.85 * breath)[np.newaxis, :]

    peak = float(np.max(np.abs(signal)))
    return (signal / peak * amplitude) if peak > 0.0 else signal


def circular_delay_mix(signal: np.ndarray, taps: list[tuple[float, float, float]]) -> np.ndarray:
    """Periodic multi-tap ambience. Pan is -1=left, +1=right."""
    wet = np.zeros_like(signal)
    for delay_seconds, gain, pan in taps:
        delay_samples = int(round(delay_seconds * SAMPLE_RATE))
        delayed = np.roll(signal.mean(axis=0), delay_samples)
        left_gain, right_gain = constant_power_pan(pan)
        wet[0] += delayed * gain * left_gain
        wet[1] += delayed * gain * right_gain
    return signal + wet


def apply_periodic_wow(signal: np.ndarray, total: int = SAMPLE_COUNT) -> np.ndarray:
    sample_positions = np.arange(total, dtype=np.float64)
    seconds = sample_positions / SAMPLE_RATE
    # The two LFOs complete integer cycles over 60 seconds, preserving the exact loop seam.
    offset = 3.8 * np.sin(2.0 * math.pi * 0.20 * seconds)
    offset += 1.7 * np.sin(2.0 * math.pi * 0.70 * seconds + 0.8)
    source = (sample_positions + offset) % total
    lower = np.floor(source).astype(np.int64)
    upper = (lower + 1) % total
    fraction = source - lower
    result = np.empty_like(signal)
    for channel in range(2):
        result[channel] = signal[channel, lower] * (1.0 - fraction) + signal[channel, upper] * fraction
    return result


def apply_memory_breath(
    signal: np.ndarray,
    depth: float = 0.21,
    total: int = SAMPLE_COUNT,
    duration: float = DURATION_SECONDS,
) -> np.ndarray:
    """Apply four exact slow breaths across the loop without moving the seam."""
    seconds = np.arange(total, dtype=np.float64) / SAMPLE_RATE
    phase = 2.0 * math.pi * 4.0 * seconds / duration
    breath = (1.0 - depth) + depth * (0.5 - 0.5 * np.cos(phase))
    return signal * breath[np.newaxis, :]


def desolation_toll(
    rng: np.random.Generator,
    midi_note: int,
    amplitude: float,
    pan: float,
    soothing: bool = False,
) -> np.ndarray:
    """A low hollow struck resonance — something abandoned that still sounds.

    Dark by construction. The partials are low and slightly inharmonic so it tolls
    rather than rings, each one decaying faster than the one below it, and the mallet
    noise is smoothed with a 40-sample window that nulls around 1.2 kHz — deliberately
    dull to reduce brightness. Synthesis and seeded noise only.
    """
    seconds = 4.2
    length = int(seconds * SAMPLE_RATE)
    t = np.arange(length, dtype=np.float64) / SAMPLE_RATE
    frequency = midi_frequency(midi_note)

    signal = np.zeros(length, dtype=np.float64)
    for ratio, weight, decay in (
        (1.000, 1.00, 1.95),
        (1.503, 0.34, 1.15),
        (2.009, 0.19, 0.74),
        (2.994, 0.08, 0.42),
    ):
        signal += weight * np.exp(-t / decay) * np.sin(
            2.0 * math.pi * frequency * ratio * t + ratio * 0.61,
        )

    strike_length = min(length, int(0.10 * SAMPLE_RATE))
    noise = rng.standard_normal(strike_length)
    kernel = np.ones(40, dtype=np.float64) / 40.0
    noise = np.convolve(noise, kernel, mode="same")
    noise *= np.exp(-np.arange(strike_length) / (0.030 * SAMPLE_RATE))
    # The mallet noise and the 5 ms attack are what make the entry a hit rather than a
    # bloom, so `soothing` (V11 onward) halves the noise and opens the attack tenfold.
    signal[:strike_length] += noise * (0.15 if soothing else 0.30)

    attack_tau = 0.050 if soothing else 0.005
    signal = np.tanh(signal * 0.75) * (1.0 - np.exp(-t / attack_tau)) * amplitude
    left_gain, right_gain = constant_power_pan(pan)
    return np.vstack((signal * left_gain, signal * right_gain)).astype(np.float32)


def opening_arch(
    depth: float = 0.48,
    total: int = SAMPLE_COUNT,
    duration: float = DURATION_SECONDS,
) -> np.ndarray:
    """A shallow swell across the whole loop, thinnest at the seam.


    The obvious fix — fade the master in — is wrong on a loop: it would dip the mix
    every 60 s at the boundary. A one-cycle raised cosine over the whole minute has no
    boundary at all (it is periodic by definition), eases the busy layers in over the
    first quarter, and as a bonus puts the loop point at the quietest moment in the
    piece, where a seam is hardest to hear.

    Applied only to the event layers — piano, glass, reverse grains. The harmonic bed,
    the toll and the wave stay continuous, so the piece thins rather than fades.
    """
    seconds = np.arange(total, dtype=np.float64) / SAMPLE_RATE
    swell = 0.5 - 0.5 * np.cos(2.0 * math.pi * seconds / duration)
    return (1.0 - depth) + depth * swell


def flatten_sixth(midi_note: int) -> int:
    """D Dorian -> D Aeolian: lower the natural 6th (B) by a semitone.

    Pitch class 11 is B, the sixth degree above D. Flattening it is the whole
    difference between the modal, faintly hopeful Dorian and the natural minor the
    gothic reference sits in.
    """
    return midi_note - 1 if midi_note % 12 == 11 else midi_note


def loop_locked_frequency(
    frequency: float,
    duration: float = DURATION_SECONDS,
) -> float:
    """Snap a frequency to the nearest whole number of cycles per loop.

    Every other voice in this file is enveloped, so its tail wraps harmlessly through
    `add_loop`. A *sustained* drone has no envelope to hide behind: if it does not
    complete an exact integer number of cycles across the loop it arrives back at the
    seam with the wrong phase, which is a click. D2 is 73.4162 Hz — 4404.97 cycles in
    60 s — so it gets nudged to 4405, a shift of half a millihertz. Inaudible, and it
    makes the drone loop-exact by construction rather than by luck.
    """
    cycles = round(frequency * duration)
    return max(cycles, 1) / duration


def mystic_hum(
    rng: np.random.Generator,
    root_midi: int,
    amplitude: float,
    total: int = SAMPLE_COUNT,
    duration: float = DURATION_SECONDS,
) -> np.ndarray:
    """A deep evolving drone with a damp organic floor under it.

     Two parts.

    The hum is a stretched, slightly inharmonic partial series — the kind a large
    resonant body has rather than a string — where every partial breathes on its own
    slow LFO at a *different* whole number of cycles per loop. Because those counts are
    coprime the spectrum keeps rotating and never repeats inside the minute, which is
    what makes a drone feel alive instead of held. All of them still close exactly at
    the seam.

    The floor is quiet band-limited noise, damp and dark, drifting on its own cycle —
    wet ground rather than air. It is deliberately in a different band from the
    breathing bed so the two do not turn into mush.

    The upper partials are weighted more than a natural series would be, for the same
    reason V4 gave the resonance pulses harmonics: the fundamental sits at 73 Hz where
    no handheld can follow, and the ear reconstructs the depth from what it can hear.
    """
    seconds = np.arange(total, dtype=np.float64) / SAMPLE_RATE
    root = midi_frequency(root_midi)
    signal = np.zeros((2, total), dtype=np.float64)

    # (partial ratio, weight, LFO cycles per loop, phase, pan)
    # Weighted upward against a natural series. A drone this deep would otherwise put
    # 85% of its own energy under 300 Hz and hand back most of the weight V13 moved into
    # the audible window; measured, this balance gives up 20 points of that and keeps a
    # real fundamental rather than only implying one.
    partials = (
        (1.000, 0.42, 1, 0.00, -0.10),
        (2.000, 0.38, 2, 0.90, 0.14),
        (3.008, 0.40, 3, 1.90, -0.22),
        (4.021, 0.52, 5, 2.70, 0.18),
        (5.039, 0.46, 7, 0.40, -0.16),
        (6.062, 0.36, 4, 3.30, 0.24),
        (8.110, 0.22, 6, 1.20, -0.20),
    )
    for ratio, weight, cycles, phase, pan in partials:
        frequency = loop_locked_frequency(root * ratio, duration)
        breathe = 0.45 + 0.55 * (
            0.5 - 0.5 * np.cos(2.0 * math.pi * cycles * seconds / duration + phase)
        )
        tone = np.sin(2.0 * math.pi * frequency * seconds + phase)
        mono = weight * breathe * tone
        left_gain, right_gain = constant_power_pan(pan)
        signal[0] += mono * left_gain
        signal[1] += mono * right_gain

    noise = rng.standard_normal((2, total))
    frequencies = np.fft.rfftfreq(total, d=1.0 / SAMPLE_RATE)
    ratio = np.maximum(frequencies, 1e-9) / 120.0
    damp = (ratio**2 / np.sqrt(1.0 + ratio**4)) / np.sqrt(1.0 + (frequencies / 520.0) ** 4)
    floor = np.fft.irfft(
        np.fft.rfft(noise, axis=1) * damp[np.newaxis, :], n=total, axis=1,
    )
    peak = float(np.max(np.abs(floor)))
    if peak > 0.0:
        drift = 0.40 + 0.60 * (
            0.5 - 0.5 * np.cos(2.0 * math.pi * 3.0 * seconds / duration + 2.1)
        )
        signal += (floor / peak) * 0.24 * drift[np.newaxis, :]

    peak = float(np.max(np.abs(signal)))
    return (signal / peak * amplitude) if peak > 0.0 else signal


def loop_edge_fade(
    signal: np.ndarray,
    seconds: float = 3.0,
    total: int = SAMPLE_COUNT,
) -> np.ndarray:
    """Fade the head in and the tail out over `seconds` each.

     Both ends reach zero, so the
    wrap is still continuous — 0 meets 0, no click — but this is a real trade against
    everything else in this file: the loop now has a dip at its boundary instead of an
    invisible join. That is a deliberate choice, not an oversight, and the size of the
    dip is measured in the evidence README.

    Shaped as sin^2 rather than linear so the onset leaves silence gently instead of
    ramping at a constant rate.
    """
    span = max(1, int(round(seconds * SAMPLE_RATE)))
    envelope = np.ones(total, dtype=np.float64)
    ramp = np.sin(np.linspace(0.0, math.pi / 2.0, span)) ** 2
    envelope[:span] = ramp
    envelope[-span:] = ramp[::-1]
    return signal * envelope[np.newaxis, :]


def trim_subsonic_weight(
    signal: np.ndarray,
    corner: float = 120.0,
    reduction_db: float = 5.0,
) -> np.ndarray:
    """Reduce weight below `corner`, which no handheld driver can radiate.

    Device-neutral, not a per-device correction: this removes content that is inaudible
    on *every* handheld rather than compensating for one of them. The Odin 2 Portal is
    about 25 dB down at 100 Hz, and its published tuning notes warn of distortion if
    100 Hz is pushed harder than +2.5 dB. Because the master is normalised to a fixed
    RMS afterwards, energy taken out down here comes back as level in the band that can
    actually be heard. Headphones keep a shelf, not a hole.
    """
    frequencies = np.fft.rfftfreq(signal.shape[1], d=1.0 / SAMPLE_RATE)
    blend = 1.0 / (1.0 + (np.maximum(frequencies, 1e-9) / corner) ** 2)
    response = 1.0 - (1.0 - 10.0 ** (-reduction_db / 20.0)) * blend
    spectrum = np.fft.rfft(signal, axis=1) * response[np.newaxis, :]
    return np.fft.irfft(spectrum, n=signal.shape[1], axis=1)


def tame_high_shelf(signal: np.ndarray) -> np.ndarray:
    """A gentle downward shelf above 2.5 kHz (V7 only).

     The 2.8-5.7 kHz octave holds
    well under 1% of this piece's energy, so this barely registers as a level change —
    but it is the band the ear is most sensitive to, so it dominates the impression of
    brightness. A shelf rather than a filter: the top is reduced, never removed.
    """
    frequencies = np.fft.rfftfreq(signal.shape[1], d=1.0 / SAMPLE_RATE)
    # Unity below the corner, easing to about -5 dB by 6 kHz and holding there.
    blend = 1.0 / (1.0 + (2500.0 / np.maximum(frequencies, 1e-9)) ** 2)
    response = 1.0 - (1.0 - 10.0 ** (-5.0 / 20.0)) * blend
    spectrum = np.fft.rfft(signal, axis=1) * response[np.newaxis, :]
    return np.fft.irfft(spectrum, n=signal.shape[1], axis=1)


def render(arrangement: str = "v1") -> np.ndarray:
    if arrangement not in ARRANGEMENTS:
        raise ValueError(f"unsupported arrangement: {arrangement}")
    # V4 keeps V3's composition exactly — same harmony, same piano performance, same
    # resonance events — and changes only how those existing voices are voiced, so the
    # music survives a handheld speaker. It adds no new layer.
    speaker_tuned = introduced_at("v4", arrangement)
    # V5 keeps all of that and attacks the "sounds synthetic" problem instead: a real
    # diffuse room in place of six bare echoes, a noise floor so the silence is not
    # digitally black, performance timing that is not exactly on the grid, and a less
    # DX7-like glass voice.
    realistic = introduced_at("v5", arrangement)
    # V6 adds the specified rhythmic element: a breathing noise texture.
    # V16,  The breathing bed introduced in
    # V6 — and reshaped into the sailing wave in V10 — is retired here. Written as an
    # explicit end rather than by dropping v16 from a set: cumulative-by-construction is
    # what keeps a layer from vanishing by accident, so a deliberate removal has to say
    # so, or the next version silently brings it back.
    breathing = (
        introduced_at("v6", arrangement) and not introduced_at("v16", arrangement)
    )
    # V7,  — V4 lifted the piano's
    # upper partials for handheld presence and overshot. V7 keeps the 1-2 kHz body that
    # carries on a speaker and pulls back the 3-5 kHz sizzle. It also raises the
    # breathing bed, which measured 23 dB down in its own band, and moves the harmony
    # toward the gothic two-chord hover.
    subdued = introduced_at("v7", arrangement)
    # V8, distil the two-chord
    # hover. The cited progression is I->iii in a major key, which cannot be dropped
    # onto this melody — the rune motif is built on F-natural and C-natural and would
    # have to be rewritten against D major / F#m. What transfers is the *shape*: two
    # chords, two common tones, one voice moving, rocked indefinitely. In this piece
    # that is i <-> III (Dm <-> F), the minor-mode mirror of I->iii.
    #
    # V8 also flattens the sixth. D Dorian's natural 6 (B) is the single note keeping
    # this out of gothic territory; B-flat makes it D Aeolian, which is the mode the
    # reference actually lives in. That is a handful of notes, not a rewrite.
    gothic = introduced_at("v8", arrangement)
    # V9: the swell waits between phrases while the piano part moves more, without a tempo change —
    # the loop stays 16 bars at 64 BPM, so 60.000 s and the seam are untouched.
    flowing = introduced_at("v9", arrangement)
    # V10,  80 BPM gives a
    # 0.75 s beat and a 3.0 s bar, so 20 bars is exactly 60.000 s — the loop length,
    # the sample count and therefore the seam are all untouched; only the musical grid
    # inside the loop changes. The tempo values are locals rather than module
    # constants precisely so V1-V9 keep rendering byte-for-byte as before.
    retimed = introduced_at("v10", arrangement)
    desolate = introduced_at("v10", arrangement)
    # V11,  A 1.0 s beat and a 4.0 s
    # bar makes 15 bars exactly 60.000 s, so the loop length and seam survive again.
    unhurried = introduced_at("v11", arrangement)
    # V12,  Measured, seconds 9-10 carry 15.8% and 18.4% of their energy
    # above 1.5 kHz against 1-3% elsewhere, and the spectral centroid jumps from about
    # 400 Hz to 930 Hz. The cause is one entry in the arrangement: bar 2 played the
    # opening cell transposed up a full octave. V12 removes that lift and turns the
    # answering phrases into descents, which is what actually reads as melancholy —
    # a falling line, and the flat sixth leaning down onto the fifth.
    mournful = introduced_at("v12", arrangement)
    # V13,  Both handheld
    # drivers fail in opposite directions — the Odin 2 Portal loses everything under
    # 300 Hz, the RP5 exaggerates 2.5-4.5 kHz by up to 28 dB — so the band that is safe
    # on both, assuming no device EQ at all, is roughly 300 Hz to 2 kHz. V12 had drifted
    # 42.1% of its energy below the Odin's corner and only 57.4% into that window.
    #
    # The fix is orchestration, not equalisation: the sustained voices are doubled an
    # octave up, which lands their weight at 330-440 Hz instead of 165-220 Hz. The
    # harmony, the register of the melody and the low end on headphones are all
    # unchanged — a quiet octave above an existing voice adds presence without moving
    # a single note of the composition. This stays one device-neutral master.
    weighted = introduced_at("v13", arrangement)
    # V14. Three things.
    #
    # (1) Phrase lengths divide the loop form to preserve harmonic rhythm Confirmed and it was a
    #     defect, not a taste call: at 60 BPM the form is 15 bars, but the harmonic
    #     rhythm was a 2-bar hover and the memory bed a 2-bar phrase. Neither divides
    #     15, so bar 14 got a one-bar chord AND no memory bed at all, immediately before
    #     the loop wrapped. Phrase lengths now have to divide the form.
    # (2) A matched head/tail fade preserves the loop seam — an explicit fade at the head, paired with
    #     a matching one at the tail so the wrap stays continuous.
    # (3) Detuned voices add beating without extra dissonant notes — read as real beating between
    #     detuned voices rather than added dissonant notes, plus an asymmetric phrase.
    dark = introduced_at("v14", arrangement)
    # V15,
    forest = introduced_at("v15", arrangement)
    # V17,
    # Measured on V16: 10 of 119 transitions leapt a fifth or more, including six full
    # octaves, a +14 and a -17. The cause was register transposition — the tail section
    # dropped alternate bars an octave and jumped back, flipping register every four
    # seconds. V17 removes every transposition and rewrites the cells as one stepwise
    # line whose junctions connect, so the melody walks instead of hopping.
    continuous = introduced_at("v17", arrangement)
    # V18,
    #
    # V17 dropped the device-neutral window to 44.4% because removing the octave
    # register left the whole melody's fundamentals at 196-294 Hz, just under the
    # Odin's corner. The V13 lever was octave-doubling the sustained voices; here it is
    # extended to the melody itself — a quiet shadow an octave above puts the same line
    # at 392-588 Hz without altering a single note of it.
    grounded = introduced_at("v18", arrangement)
    # V19,  50 BPM gives a 4.8 s bar in 4/4, and
    # 4.8 does not divide 60 — the loop would need 12.5 bars. Only tempos that are a
    # multiple of 4 BPM land an integer bar count on a 60 s loop, so 48 or 52 would fit
    # and 50 cannot. The 60 s length was never a product requirement; it fell out of V1.
    # So the tempo is honoured exactly and the loop becomes 12 bars of 4.8 s = 57.600 s.
    # Loop length is now a local for the same reason tempo is: every earlier arrangement
    # keeps its own, unchanged.
    slower = introduced_at("v19", arrangement)
    # V20,  Read as shaped descending figures -- notes that fall in both pitch
    # and dynamic across a phrase -- that thicken the melodic line itself, not another
    # bed underneath it. See the `if richness:` block below for what that becomes.
    richness = introduced_at("v20", arrangement)
    # Each existing dynamic figure gains one octave note; the decrescendo shape stays intact.
    # This is additive: richness above stays unchanged, with no new figure or bar.
    # The nested if keening block carries the mechanism and explains the octave interval.
    keening = introduced_at("v21", arrangement)
    # V22: the same cry, moved into the band the handheld can actually radiate. See the
    # `if audible_cry:` branch below for why V21's octave landed under the driver.
    audible_cry = introduced_at("v22", arrangement)
    beat_seconds = 1.2 if slower else (
        1.0 if unhurried else (0.75 if retimed else BEAT_SECONDS)
    )
    bar_seconds = beat_seconds * 4.0
    bar_count = 12 if slower else (
        15 if unhurried else (20 if retimed else BAR_COUNT)
    )
    duration = bar_seconds * bar_count
    sample_count = int(round(duration * SAMPLE_RATE))
    humanise = np.random.default_rng(RNG_SEED ^ 0x5A5A5A5A)
    rng = np.random.default_rng(RNG_SEED)
    # V21's fifth note (see `if keening:` below) draws from its own generators rather
    # than from `humanise`/`rng`. Every voice after it in this function -- heartbeat,
    # desolation_toll, the reverb impulse, mystic_hum's floor, air_bed -- shares those
    # two streams sequentially, so any extra draw shifts every later voice's noise
    # realization even though the render stays fully deterministic. That is invisible
    # to the byte-identity gate (v1-v20 never touch this code at all, since `keening`
    # is false for them), but it would make V20-vs-V21 differ far beyond the four
    # notes actually asked for. Dedicated streams keep the diff surgical.
    keening_humanise = np.random.default_rng(RNG_SEED ^ 0x4B45454E)  # "KEEN"
    keening_noise = np.random.default_rng(RNG_SEED ^ 0x43525921)  # "CRY!"
    piano = np.zeros((2, sample_count), dtype=np.float32)
    pad = np.zeros_like(piano)
    pulse = np.zeros_like(piano)
    glass = np.zeros_like(piano)
    memory = np.zeros_like(piano)
    resonance = np.zeros_like(piano)
    toll = np.zeros_like(piano)
    hum = np.zeros_like(piano)

    # D-Dorian voicings. MIDI pitches are authored here; no reference MIDI or audio is read.
    chord_map = (
        (38, 45, 52, 64),  # Dm(add9)
        (38, 45, 52, 57),  # D5(add9)
        (38, 43, 50, 64),  # C(add9)/D
        (38, 43, 47, 52),  # G6/D
        (38, 47, 53, 57),  # Bm7b5/D
        (38, 43, 50, 64),
        (38, 43, 45, 50),  # Gsus2/D
        (38, 45, 52, 64),
        (38, 45, 52, 64),
        (38, 43, 47, 60),  # Cmaj7/D
        (38, 43, 47, 52),
        (36, 41, 48, 55),  # F(add9)/C
        (38, 43, 45, 50),
        (38, 43, 50, 64),
        (38, 45, 52, 57),
        (38, 45, 52, 64),
    )
    if gothic:
        # Dm and F share F and A; the bass moves D->F and the top voice holds. Rocked
        # eight times across the loop, which is what makes it a hover and not a journey.
        hover_i = (38, 45, 53, 57)     # D2 A2 F3 A3  — i
        hover_iii = (41, 48, 53, 57)   # F2 C3 F3 A3  — III
        hover_vi = (34, 41, 50, 57)    # Bb1 F2 D3 A3 — VI, a Bbmaj7 colour
        if dark:
            # 15 = 3 x 5, so a five-bar phrase divides the form exactly and every chord
            # gets its full length — including the last one before the wrap. The 2+2+1
            # grouping is deliberately asymmetric: it never settles into a square four,
            # which is most of what makes a hover feel like an adventure rather than a
            # rocking chair. VI resolving to i across the loop point closes the circle.
            candidates = (
                (hover_i, hover_i, hover_iii, hover_iii, hover_vi),
                (hover_i, hover_i, hover_iii, hover_vi),
                (hover_i, hover_iii, hover_vi),
            )
            phrase = next(
                (c for c in candidates if bar_count % len(c) == 0), None,
            )
            if phrase is None:
                raise ValueError(f"no phrase length divides a {bar_count}-bar form")
            chord_map = tuple(phrase[bar % len(phrase)] for bar in range(bar_count))
        else:
            chord_map = tuple(
                hover_i if (bar // 2) % 2 == 0 else hover_iii for bar in range(bar_count)
            )

    for bar, chord in enumerate(chord_map):
        bar_start = int(round(bar * bar_seconds * SAMPLE_RATE))
        for voice, midi_note in enumerate(chord):
            mono = synth_pad_note(
                midi_note=flatten_sixth(midi_note) if gothic else midi_note,
                seconds=bar_seconds + 0.32,
                amplitude=0.026 if voice < 2 else 0.018,
                phase=bar * 0.41 + voice * 0.77,
            )
            left_gain, right_gain = constant_power_pan(-0.32 + voice * 0.21)
            add_loop(pad, np.vstack((mono * left_gain, mono * right_gain)), bar_start)
        if weighted:
            # One octave above the chord's top voice. A3 becomes A4, F3 becomes F4 —
            # from just under the Odin's corner to comfortably inside the window.
            top = chord[-1] + 12
            mono = synth_pad_note(
                midi_note=flatten_sixth(top) if gothic else top,
                seconds=bar_seconds + 0.32,
                amplitude=0.030 if grounded else 0.015,
                phase=bar * 0.41 + 3.08,
            )
            left_gain, right_gain = constant_power_pan(0.12)
            add_loop(pad, np.vstack((mono * left_gain, mono * right_gain)), bar_start)
            if dark:
                # A second voice eight cents away from the first. Two near-identical
                # pitches beat against each other at a couple of hertz — disharmony you
                # hear as a slow pulse rather than as a wrong note, and the rate rises
                # with pitch on its own, so the layers never lock together.
                mono = synth_pad_note(
                    midi_note=(flatten_sixth(top) if gothic else top) + 0.08,
                    seconds=bar_seconds + 0.32,
                    amplitude=0.012,
                    phase=bar * 0.41 + 1.73,
                )
                left_gain, right_gain = constant_power_pan(-0.16)
                add_loop(pad, np.vstack((mono * left_gain, mono * right_gain)), bar_start)

    if introduced_at("v2", arrangement):
        # A slower two-bar harmonic shadow reinforces the existing D-Dorian map
        # without changing its progression or the authored piano performance.
        memory_voicings = (
            (38, 45, 52),
            (38, 43, 50),
            (38, 47, 53),
            (36, 43, 48),
            (38, 43, 45),
            (38, 45, 52),
            (38, 43, 47),
            (38, 45, 52),
        ) + ((38, 43, 50), (38, 45, 52))
        # Two bars per phrase, so the count follows the form rather than a literal.
        # A two-bar phrase leaves the last bar of an odd form bare. Pick a length that
        # divides the form instead of assuming two.
        phrase_bars = 2 if not dark else next(
            n for n in (2, 3, 5) if bar_count % n == 0
        )
        memory_voicings = memory_voicings[: bar_count // phrase_bars]
        for phrase, chord in enumerate(memory_voicings):
            phrase_start = int(round(phrase * phrase_bars * bar_seconds * SAMPLE_RATE))
            for voice, midi_note in enumerate(chord):
                note = warm_memory_note(
                    midi_note=flatten_sixth(midi_note) if gothic else midi_note,
                    seconds=phrase_bars * bar_seconds + 1.25,
                    amplitude=0.036 if voice == 0 else 0.023,
                    phase=phrase * 0.37 + voice * 0.83,
                    pan=-0.30 + voice * 0.30,
                )
                add_loop(memory, note, phrase_start)
            if weighted:
                top = chord[-1] + 12
                add_loop(memory, warm_memory_note(
                    midi_note=flatten_sixth(top) if gothic else top,
                    seconds=phrase_bars * bar_seconds + 1.25,
                    amplitude=0.026 if grounded else 0.014,
                    phase=phrase * 0.37 + 2.49,
                    pan=0.22,
                ), phrase_start)
                if dark:
                    add_loop(memory, warm_memory_note(
                        midi_note=(flatten_sixth(top) if gothic else top) - 0.07,
                        seconds=phrase_bars * bar_seconds + 1.25,
                        amplitude=0.011,
                        phase=phrase * 0.37 + 0.94,
                        pan=-0.26,
                    ), phrase_start)
        memory = apply_memory_breath(memory, total=sample_count, duration=duration)
        pad = apply_memory_breath(
            pad, depth=0.065, total=sample_count, duration=duration,
        )

    # Original rune cells in D Dorian. Each tuple is (beat, MIDI, held beats, velocity, pan).
    cell_a = ((0.0, 62, 0.42, 0.34, -0.16), (0.5, 65, 0.42, 0.30, 0.10),
              (1.0, 67, 0.82, 0.33, -0.04), (2.0, 69, 0.42, 0.30, 0.14),
              (2.5, 67, 0.42, 0.28, -0.10))
    cell_b = ((0.0, 60, 0.42, 0.30, 0.14), (0.5, 71, 0.42, 0.27, -0.12),
              (1.0, 69, 0.82, 0.31, 0.05), (2.0, 67, 0.42, 0.28, -0.12),
              (2.5, 65, 0.42, 0.27, 0.10))
    if flowing:
        # Same contour, same pitches, roughly twice the events: the figure runs in
        # eighths instead of resting between gestures. Held time and velocity come down
        # so the denser part does not simply read as louder and muddier.
        cell_a = ((0.0, 62, 0.34, 0.31, -0.16), (0.5, 65, 0.30, 0.27, 0.10),
                  (1.0, 67, 0.44, 0.30, -0.04), (1.5, 69, 0.28, 0.22, 0.08),
                  (2.0, 69, 0.32, 0.27, 0.14), (2.5, 67, 0.30, 0.25, -0.10),
                  (3.0, 65, 0.30, 0.22, 0.06), (3.5, 62, 0.32, 0.20, -0.08))
        cell_b = ((0.0, 60, 0.34, 0.27, 0.14), (0.5, 70, 0.30, 0.24, -0.12),
                  (1.0, 69, 0.44, 0.28, 0.05), (1.5, 67, 0.28, 0.22, -0.08),
                  (2.0, 67, 0.32, 0.25, -0.12), (2.5, 65, 0.30, 0.24, 0.10),
                  (3.0, 62, 0.30, 0.21, 0.06), (3.5, 65, 0.32, 0.19, -0.06))

    if continuous:
        # Every cell ends within a step or two of where the next begins, so the phrase
        # boundaries stop announcing themselves. Held time is longer than the 0.5-beat
        # spacing, so consecutive notes overlap — legato rather than a row of separate
        # strikes, which is most of what "continuous" means here.
        cell_a = ((0.0, 62, 0.58, 0.30, -0.14), (0.5, 65, 0.56, 0.27, 0.10),
                  (1.0, 67, 0.58, 0.29, -0.06), (1.5, 69, 0.56, 0.25, 0.08),
                  (2.0, 69, 0.58, 0.27, 0.12), (2.5, 67, 0.56, 0.25, -0.10),
                  (3.0, 65, 0.58, 0.23, 0.06), (3.5, 62, 0.60, 0.22, -0.08))
        cell_b = ((0.0, 60, 0.58, 0.27, 0.12), (0.5, 58, 0.56, 0.25, -0.10),
                  (1.0, 57, 0.58, 0.26, 0.06), (1.5, 58, 0.56, 0.23, -0.08),
                  (2.0, 60, 0.58, 0.25, 0.10), (2.5, 62, 0.56, 0.24, -0.06),
                  (3.0, 65, 0.58, 0.23, 0.08), (3.5, 64, 0.60, 0.21, -0.10))

    motif_events: list[tuple[int, tuple[tuple[float, int, float, float, float], ...], int]] = [
        (0, cell_a, 0), (1, cell_b, 0), (2, cell_a, 12), (3, cell_b, 0),
    ][: 3 if unhurried else 4]
    if mournful:
        # A descent in the same register replaces the octave leap: same role in the
        # form (bar 2 answers bars 0-1) with none of the brightness. B-flat leaning
        # down onto A is the plainest sigh available in this mode.
        cell_fall = ((0.0, 70, 0.34, 0.28, -0.14), (0.5, 69, 0.30, 0.26, 0.10),
                     (1.0, 67, 0.38, 0.27, -0.06), (1.5, 65, 0.30, 0.24, 0.08),
                     (2.0, 64, 0.32, 0.23, 0.12), (2.5, 62, 0.34, 0.25, -0.10),
                     (3.0, 60, 0.30, 0.21, 0.06), (3.5, 62, 0.34, 0.22, -0.08))
        if continuous:
            cell_fall = ((0.0, 65, 0.58, 0.28, -0.12), (0.5, 64, 0.56, 0.26, 0.10),
                     (1.0, 62, 0.58, 0.27, -0.06), (1.5, 60, 0.56, 0.24, 0.08),
                     (2.0, 58, 0.58, 0.25, 0.12), (2.5, 57, 0.56, 0.24, -0.10),
                     (3.0, 58, 0.58, 0.22, 0.06), (3.5, 60, 0.60, 0.21, -0.08))
        motif_events = [
            (bar, cell_fall if transpose == 12 else events, 0 if transpose == 12 else transpose)
            for bar, events, transpose in motif_events
        ]
    tail = ((1.0, 67, 0.55, 0.27, -0.12), (2.0, 69, 0.42, 0.25, 0.12),
            (2.5, 67, 0.48, 0.24, -0.06))
    if flowing:
        tail = ((0.0, 62, 0.30, 0.20, -0.10), (0.5, 65, 0.28, 0.18, 0.08),
                (1.0, 67, 0.38, 0.24, -0.12), (1.5, 69, 0.28, 0.19, 0.10),
                (2.0, 69, 0.30, 0.22, 0.12), (2.5, 67, 0.30, 0.21, -0.06),
                (3.0, 65, 0.28, 0.19, 0.06), (3.5, 62, 0.32, 0.17, -0.08))
    if continuous:
        tail = ((0.0, 62, 0.58, 0.24, -0.10), (0.5, 64, 0.56, 0.22, 0.08),
                (1.0, 65, 0.58, 0.25, -0.12), (1.5, 67, 0.56, 0.23, 0.10),
                (2.0, 65, 0.58, 0.24, 0.12), (2.5, 64, 0.56, 0.22, -0.06),
                (3.0, 62, 0.58, 0.21, 0.06), (3.5, 60, 0.60, 0.20, -0.08))
    if slower:
        tail_bars, answer_bars, reduced_bars = range(3, 6), range(6, 9), range(9, 12)
    elif unhurried:
        tail_bars, answer_bars, reduced_bars = range(3, 7), range(7, 11), range(11, 15)
    elif retimed:
        tail_bars, answer_bars, reduced_bars = range(4, 9), range(9, 14), range(14, 20)
    else:
        tail_bars, answer_bars, reduced_bars = range(4, 8), range(8, 12), range(12, 16)
    for bar in tail_bars:
        motif_events.append((bar, tail, 0 if bar % 2 == 0 else -12))
    high_answer = ((0.5, 72, 0.45, 0.24, 0.15), (1.5, 71, 0.68, 0.27, -0.12),
                   (2.75, 69, 0.50, 0.23, 0.08))
    if flowing:
        high_answer = ((0.0, 72, 0.28, 0.20, 0.12), (0.5, 70, 0.28, 0.19, -0.10),
                       (1.0, 69, 0.34, 0.21, 0.08), (1.5, 67, 0.28, 0.20, -0.12),
                       (2.0, 69, 0.28, 0.19, 0.10), (2.5, 72, 0.28, 0.18, -0.08),
                       (3.0, 70, 0.32, 0.20, 0.06), (3.5, 69, 0.30, 0.17, -0.06))
    if mournful:
        # Was an oscillation around C5, which kept re-striking the top of the range.
        # Now it leaves from there once and falls an octave, and never returns.
        high_answer = ((0.0, 72, 0.30, 0.19, 0.12), (0.5, 70, 0.28, 0.19, -0.10),
                       (1.0, 69, 0.32, 0.20, 0.08), (1.5, 67, 0.28, 0.19, -0.12),
                       (2.0, 65, 0.30, 0.19, 0.10), (2.5, 64, 0.28, 0.18, -0.08),
                       (3.0, 62, 0.34, 0.19, 0.06), (3.5, 60, 0.32, 0.17, -0.06))
    if continuous:
        high_answer = ((0.0, 62, 0.58, 0.23, 0.10), (0.5, 60, 0.56, 0.22, -0.08),
                       (1.0, 58, 0.58, 0.24, 0.06), (1.5, 57, 0.56, 0.22, -0.12),
                       (2.0, 55, 0.58, 0.23, 0.08), (2.5, 57, 0.56, 0.21, -0.06),
                       (3.0, 58, 0.58, 0.21, 0.10), (3.5, 60, 0.60, 0.20, -0.08))
    for bar in answer_bars:
        motif_events.append((bar, high_answer, 0 if bar != answer_bars[-1] else -12))
    reduced = ((0.0, 62, 0.70, 0.27, -0.12), (1.5, 67, 0.55, 0.24, 0.10),
               (3.0, 69, 0.48, 0.22, -0.04))
    if flowing:
        reduced = ((0.0, 62, 0.32, 0.22, -0.12), (0.5, 65, 0.28, 0.18, 0.08),
                   (1.0, 67, 0.28, 0.20, -0.06), (1.5, 69, 0.34, 0.22, 0.10),
                   (2.0, 67, 0.28, 0.19, -0.08), (2.5, 65, 0.28, 0.18, 0.06),
                   (3.0, 69, 0.32, 0.20, -0.04), (3.5, 67, 0.30, 0.17, 0.08))
    if continuous:
        reduced = ((0.0, 62, 0.58, 0.24, -0.12), (0.5, 64, 0.56, 0.21, 0.08),
                   (1.0, 65, 0.58, 0.23, -0.06), (1.5, 64, 0.56, 0.22, 0.10),
                   (2.0, 62, 0.58, 0.22, -0.08), (2.5, 60, 0.56, 0.20, 0.06),
                   (3.0, 58, 0.58, 0.21, -0.04), (3.5, 57, 0.60, 0.19, 0.08))
    for bar in reduced_bars:
        motif_events.append((bar, reduced, 0 if bar < reduced_bars[-1] else -12))

    if continuous:
        # No octave transposition anywhere. Contrast between sections now comes from the
        # contour and from the i/III/VI hover underneath, not from throwing the line
        # into another register mid-phrase.
        motif_events = [(bar, events, 0) for bar, events, _ in motif_events]

    for bar, events, transpose in motif_events:
        for beat, midi_note, held_beats, velocity, pan in events:
            position = bar * bar_seconds + beat * beat_seconds
            played_velocity = velocity
            if realistic:
                # Nothing played by hand lands exactly on the grid, and no two strikes
                # are the same weight. Both are deterministic, and `add_loop` wraps any
                # note pushed past the boundary, so the seam is unaffected.
                position += float(humanise.normal(0.0, 0.013))
                played_velocity *= float(1.0 + humanise.normal(0.0, 0.075))
            start = int(round(position * SAMPLE_RATE))
            note = felt_piano_note(
                rng=rng,
                midi_note=(
                    flatten_sixth(midi_note + transpose) if gothic
                    else midi_note + transpose
                ),
                held_seconds=held_beats * beat_seconds,
                amplitude=played_velocity,
                pan=pan,
                presence=speaker_tuned,
                subdued=subdued,
            )
            add_loop(piano, note, start)
            if grounded:
                # The same trick V13 used on the pad, applied to the melody: an octave
                # above. Measured across four levels: at 0.34 it recovers 5 points of
                # window, at 0.78 it recovers all 18 but is then a co-equal voice
                # playing the melody in octaves, which is a different line. 0.62 takes
                # most of the ground back while staying subordinate to the melody.
                add_loop(piano, felt_piano_note(
                    rng=rng,
                    midi_note=(
                        flatten_sixth(midi_note + transpose) if gothic
                        else midi_note + transpose
                    ) + 12,
                    held_seconds=held_beats * beat_seconds,
                    amplitude=played_velocity * 0.62,
                    pan=-pan * 0.7,
                    presence=speaker_tuned,
                    subdued=subdued,
                ), start)

    if grounded:
        # One note per beat walking the chord of the bar. It always moves to the chord
        # tones nearest where it just was, so it steps rather than jumps — the same
        # voice-leading rule that fixed the melody in V17 — and an explicit ceiling
        # keeps it below the melody instead of competing with it.
        PROGRESSION_FLOOR, PROGRESSION_CEILING = 43, 57
        previous = 50
        for bar in range(bar_count):
            classes = sorted({note % 12 for note in chord_map[bar]})
            tones = [
                m for m in range(PROGRESSION_FLOOR, PROGRESSION_CEILING + 1)
                if m % 12 in classes
            ]
            if not tones:
                continue
            nearest = sorted(sorted(tones, key=lambda m: abs(m - previous))[:3])
            shape = (nearest[0], nearest[min(1, len(nearest) - 1)],
                     nearest[-1], nearest[min(1, len(nearest) - 1)])
            for beat, midi_note in enumerate(shape):
                start = int(round(
                    (bar * bar_seconds + beat * beat_seconds) * SAMPLE_RATE,
                ))
                add_loop(piano, felt_piano_note(
                    rng=rng,
                    midi_note=midi_note,
                    held_seconds=beat_seconds * 0.92,
                    amplitude=0.15 if beat % 2 == 0 else 0.115,
                    pan=0.20 if beat % 2 == 0 else -0.16,
                    presence=speaker_tuned,
                    subdued=subdued,
                ), start)
                # This line sits at 110-175 Hz, entirely under the Odin's corner, and
                # measured it hands back almost exactly what the lever gains. So it gets
                # the same treatment as everything else here: the fundamental and the
                # ceiling stay exactly as written, and a quiet shadow an octave up
                # carries the weight into the audible window.
                add_loop(piano, felt_piano_note(
                    rng=rng,
                    midi_note=midi_note + 12,
                    held_seconds=beat_seconds * 0.92,
                    amplitude=(0.15 if beat % 2 == 0 else 0.115) * 0.74,
                    pan=-0.18 if beat % 2 == 0 else 0.14,
                    presence=speaker_tuned,
                    subdued=subdued,
                ), start)
            previous = shape[-1]

    if richness:
        #  Interpreted as shaped descending figures -- notes that fall in
        # both pitch and dynamic across a phrase -- that thicken the melodic line, not
        # a new pad: no new harmony is introduced here at all. Each figure is that
        # bar's own `chord_map` voicing (the exact tones already sounding in `pad`)
        # read top to bottom, so it can only ever agree with the chord already
        # sounding under it.
        #
        # One figure per existing 3-bar melodic phrase (bars 2, 5, 8, 11 -- the last
        # bar of the opening/tail/answer/reduced sections), landing on the off-beat so
        # it interlocks with the V18 walking progression's on-the-beat quarter notes
        # instead of doubling them. 3 divides the 12-bar form exactly, so every phrase
        # gets one and none is orphaned at the wrap -- guarded explicitly below rather
        # than assumed, the way an unguarded phrase length broke V14's bar 14.
        #
        # Every hover chord (i, III, VI) shares A as a common tone -- the "top voice
        # holds" note the gothic hover was built on -- so every figure starts there and
        # peels downward into that bar's own voicing. VI (bar 11) is voiced with all
        # four of root/5th/8ve/maj7 rather than a plain triad, so its figure reaches
        # the lowest, quietest note of the whole piece, right before the wrap.
        RICHNESS_PHRASE_BARS = 3
        if bar_count % RICHNESS_PHRASE_BARS != 0:
            raise ValueError(
                f"richness phrase length {RICHNESS_PHRASE_BARS} does not divide "
                f"a {bar_count}-bar form",
            )
        descent_offsets = (0.5, 1.5, 2.5, 3.5)
        descent_velocities = (0.20, 0.15, 0.11, 0.07)
        descent_pans = (0.22, 0.10, -0.05, -0.18)
        for bar in range(RICHNESS_PHRASE_BARS - 1, bar_count, RICHNESS_PHRASE_BARS):
            descent_notes = sorted(chord_map[bar], reverse=True)
            for beat, midi_note, velocity, pan in zip(
                descent_offsets, descent_notes, descent_velocities, descent_pans,
            ):
                position = bar * bar_seconds + beat * beat_seconds
                played_velocity = velocity
                if realistic:
                    position += float(humanise.normal(0.0, 0.013))
                    played_velocity *= float(1.0 + humanise.normal(0.0, 0.075))
                start = int(round(position * SAMPLE_RATE))
                add_loop(piano, felt_piano_note(
                    rng=rng,
                    midi_note=flatten_sixth(midi_note) if gothic else midi_note,
                    held_seconds=beat_seconds * 0.90,
                    amplitude=played_velocity,
                    pan=pan,
                    presence=speaker_tuned,
                    subdued=subdued,
                ), start)
            if keening:
                # The fifth note. Further down in both pitch and
                # dynamic than the four notes just written above, so it is a literal
                # octave under this figure's own lowest chord tone (still chord-tone-
                # only: same pitch class as `descent_notes[-1]`, so it can still only
                # ever agree with the chord already sounding under it) at a quieter
                # velocity than the existing 0.07 floor -- the descent keeps falling.
                #
                # The interval TO this note is an
                # octave leap, and it arrives on a sixteenth
                # (0.25 beat) after the fourth note instead of the eighth-note (1.0
                # beat) spacing the other four use, which is what makes it "rapid" in
                # rhythm rather than in level; loudness stays unchanged.
                #
                # The initial high note followed by descent is already the shape of the
                # four notes above (the figure opens on the shared A, its highest and
                # loudest point, and steps down from there); this adds only the
                # "fails" half. A fifth note that kept stepping down by a chord-tone
                # interval would read as more of the same sigh. Breaking register
                # instead -- leaving the stepwise motion and falling clear through it
                # -- is what turns that sigh into a crack in the voice, without
                # raising a single dB to say so.
                #
                # This is a real octave under a figure that already touched MIDI 34
                # unverified on handheld (measurements at this depth show:
                # felt_piano_note stays clean here -- real energy, spectral centroid
                # tracking the fundamental, no artifacts -- but on the documented
                # handheld correction curves less than half a percent of even the
                # existing MIDI 34 note's own energy survives, and this note is a
                # further octave down from that. It is a headphone/flat-monitor
                # gesture more than a handheld one, same as this piece's existing
                # sub-120 Hz content; it was not pulled up to compensate, because
                # doing so would be exactly the loudness move this figure is asking
                # to avoid).
                # Melodic figures remain audible through handheld speakers.
                #
                # V21 put this note an octave UNDER the figure's lowest chord tone, at
                # MIDI 22-29 (29-44 Hz). The Odin 2 Portal's documented corner is 300 Hz
                # with a 16 dB/octave rolloff below it, so that note sat 44-54 dB down
                # before it ever left the driver -- a gesture that existed in the file
                # and not in the room. The cry was inaudible on the only speaker that
                # matters, which makes it not a cry.
                #
                # The fix is register, not level: the octave drop now happens an octave
                # and a half higher, from `+24` down to `+12` above the figure's lowest
                # chord tone. The interval is still a literal octave and the timing is
                # still the sixteenth-note snap, so the gesture is unchanged -- only the
                # window it happens in moves, up to where the speaker radiates. Same
                # pitch class throughout, so it stays chord-tone-only and cannot clash.
                #
                # Deliberately NOT solved by raising amplitude: this figure's whole
                # premise is that loudness is not the lever.
                deepest = descent_notes[-1] + (12 if audible_cry else -12)
                position = bar * bar_seconds + 3.75 * beat_seconds
                played_velocity = 0.045
                if realistic:
                    position += float(keening_humanise.normal(0.0, 0.013))
                    played_velocity *= float(1.0 + keening_humanise.normal(0.0, 0.075))
                start = int(round(position * SAMPLE_RATE))
                add_loop(piano, felt_piano_note(
                    rng=keening_noise,
                    midi_note=flatten_sixth(deepest) if gothic else deepest,
                    held_seconds=beat_seconds * 0.45,
                    amplitude=played_velocity,
                    pan=-0.30,
                    presence=speaker_tuned,
                    subdued=subdued,
                ), start)
                if audible_cry:
                    # The octave the cry falls FROM. Without it the leap has no upper
                    # term in the audible band and reads as one stray high note; with it
                    # the ear hears the break. A hair earlier and quieter than the
                    # arrival, so the pair still descends in both pitch and dynamic.
                    reach = deepest + 12
                    reach_position = bar * bar_seconds + 3.5625 * beat_seconds
                    reach_velocity = played_velocity * 0.82
                    if realistic:
                        reach_position += float(keening_humanise.normal(0.0, 0.010))
                        reach_velocity *= float(1.0 + keening_humanise.normal(0.0, 0.060))
                    add_loop(piano, felt_piano_note(
                        rng=keening_noise,
                        midi_note=flatten_sixth(reach) if gothic else reach,
                        held_seconds=beat_seconds * 0.22,
                        amplitude=reach_velocity,
                        pan=-0.22,
                        presence=speaker_tuned,
                        subdued=subdued,
                    ), int(round(reach_position * SAMPLE_RATE)))

    # Dormant-machine heartbeat: steady but deliberately beneath the piano.
    pulse_hit = heartbeat(rng)
    for beat_index in range(bar_count * 4):
        start = int(round(beat_index * beat_seconds * SAMPLE_RATE))
        accent = 1.0 if beat_index % 4 == 0 else 0.68
        add_loop(pulse, pulse_hit * (0.16 * accent), start)

    # Sparse synthetic glass and a dotted-eighth cold echo.
    if slower:
        glass_events = ((2, 81, -0.48), (5, 79, 0.44), (7, 84, -0.36),
                        (9, 81, 0.40), (10, 86, -0.42), (11, 79, 0.32))
    elif unhurried:
        glass_events = ((3, 81, -0.48), (6, 79, 0.44), (9, 84, -0.36),
                        (11, 81, 0.40), (13, 86, -0.42), (14, 79, 0.32))
    elif retimed:
        glass_events = ((4, 81, -0.48), (9, 79, 0.44), (12, 84, -0.36),
                        (15, 81, 0.40), (17, 86, -0.42), (19, 79, 0.32))
    else:
        glass_events = ((3, 81, -0.48), (7, 79, 0.44), (10, 84, -0.36),
                        (12, 81, 0.40), (14, 86, -0.42), (15, 79, 0.32))
    for bar, midi_note, pan in glass_events:
        start_seconds = bar * bar_seconds + 2.75 * beat_seconds
        ping = fm_glass(
            midi_note - 12 if mournful else midi_note,
            amplitude=0.045 if subdued else 0.10,
            pan=pan,
            soften=realistic,
        )
        add_loop(glass, ping, int(round(start_seconds * SAMPLE_RATE)))
        add_loop(
            glass,
            ping * 0.24,
            int(round((start_seconds + 0.75 * beat_seconds) * SAMPLE_RATE)),
        )

    if arrangement == "v2":
        # An original, non-repeating glint constellation fills selected negative
        # spaces. It supplies synth motion without borrowing a reference pattern.
        glint_events = (
            (0, 1.50, 74, -0.42),
            (2, 3.15, 69, 0.36),
            (4, 0.75, 77, -0.30),
            (6, 2.40, 72, 0.44),
            (8, 1.25, 76, -0.40),
            (10, 3.25, 71, 0.32),
            (12, 0.60, 74, -0.36),
            (13, 2.70, 79, 0.42),
            (15, 1.60, 69, -0.22),
        )
        for bar, beat, midi_note, pan in glint_events:
            start_seconds = bar * bar_seconds + beat * beat_seconds
            glint = memory_glint(midi_note, amplitude=0.055, pan=pan)
            add_loop(glass, glint, int(round(start_seconds * SAMPLE_RATE)))

    if introduced_at("v3", arrangement):
        # The lower-register arrangement replaces V2's upper glints with a lower-register
        # irregular pulse. Its denser cadence supplies motion; its long body and
        # diffuse taps supply atmosphere without competing with the piano melody.
        resonance_events = (
            (0, 0.55, 50, -0.22), (0, 2.55, 57, 0.18),
            (1, 1.35, 53, 0.12),
            (2, 0.70, 48, -0.18), (2, 2.80, 55, 0.22),
            (3, 1.55, 52, -0.10),
            (4, 0.45, 50, 0.20), (4, 2.35, 57, -0.18),
            (5, 1.15, 55, 0.12),
            (6, 0.75, 53, -0.20), (6, 2.65, 60, 0.18),
            (7, 1.45, 57, -0.12),
            (8, 0.50, 48, 0.18), (8, 2.45, 55, -0.20),
            (9, 1.25, 52, 0.10),
            (10, 0.65, 50, -0.18), (10, 2.70, 57, 0.22),
            (11, 1.50, 55, -0.12),
            (12, 0.40, 48, 0.20), (12, 2.30, 53, -0.18),
            (13, 1.10, 57, 0.12),
            (14, 0.70, 50, -0.20), (14, 2.55, 55, 0.18),
            (15, 1.35, 52, -0.10),
        ) + (
            ((16, 0.50, 50, -0.20), (16, 2.45, 55, 0.18), (17, 1.20, 52, 0.12),
             (18, 0.65, 48, -0.18), (18, 2.60, 57, 0.22), (19, 1.40, 53, -0.10))
            if retimed else ()
        )
        for bar, beat, midi_note, pan in (
            event for event in resonance_events if event[0] < bar_count
        ):
            start_seconds = bar * bar_seconds + beat * beat_seconds
            note = low_resonance_pulse(
                midi_note,
                amplitude=0.044,
                pan=pan,
                speaker_harmonics=speaker_tuned,
            )
            add_loop(resonance, note, int(round(start_seconds * SAMPLE_RATE)))
        resonance = apply_memory_breath(
            resonance, depth=0.14, total=sample_count, duration=duration,
        )

    if desolate:
        #  Bars 0-6 at 80 BPM is 0-21 s. The figure leans on
        # the flat sixth (B-flat) and the fifth below the tonic — the two most plaintive
        # notes available in D Aeolian — and descends. Its level tapers bar by bar so
        # the opening dissolves into the body of the piece instead of stopping.
        if unhurried:
            #  V10 put the loudest toll at t=0 and tapered from there, so the
            # piece opened on its hardest strike — and on a loop that lands immediately
            # after the previous pass ends. This swells instead: quietest at the top,
            # cresting near 14 s, gone by 20 s. Positions are in seconds, so the
            # "sec. 1 to 20" span holds whatever the tempo is.
            #
            # A fade-in on the master would be the obvious fix and is the wrong one: it
            # would dip the mix every 60 s at the loop point. The gentleness has to live
            # in the layer, not the master.
            toll_events = (
                (1.0, 38, 0.30, -0.10), (3.5, 34, 0.38, 0.12),
                (6.0, 41, 0.52, 0.06), (8.5, 33, 0.62, -0.14),
                (10.5, 38, 0.78, -0.08), (12.5, 34, 0.88, 0.14),
                (14.0, 36, 1.00, 0.06), (15.5, 33, 0.84, -0.12),
                (17.0, 38, 0.66, -0.10), (18.5, 34, 0.48, 0.12),
                (20.0, 36, 0.32, 0.08),
            )
        else:
            toll_events = tuple(
                (bar * bar_seconds + beat * beat_seconds, midi_note, weight, pan)
                for bar, beat, midi_note, weight, pan in (
                    (0, 0.0, 38, 1.00, -0.10), (0, 2.5, 34, 0.60, 0.12),
                    (1, 1.0, 41, 0.72, 0.06), (1, 3.0, 33, 0.52, -0.14),
                    (2, 0.0, 38, 0.92, -0.08), (2, 2.5, 34, 0.58, 0.14),
                    (3, 1.0, 36, 0.68, 0.06), (3, 3.0, 33, 0.50, -0.12),
                    (4, 0.0, 38, 0.82, -0.10), (4, 2.5, 34, 0.54, 0.12),
                    (5, 1.5, 36, 0.62, 0.08),
                    (6, 0.0, 38, 0.58, -0.06),
                )
            )
        for start_seconds, midi_note, weight, pan in toll_events:
            add_loop(
                toll,
                desolation_toll(
                    rng, midi_note, amplitude=0.085 * weight, pan=pan,
                    soothing=unhurried,
                ),
                int(round(start_seconds * SAMPLE_RATE)),
            )

    if forest:
        hum = mystic_hum(
            rng, root_midi=38, amplitude=0.10,
            total=sample_count, duration=duration,
        )

    # Reverse fragments are made only from this render's original piano signal.
    machine = np.zeros_like(piano)
    grain_length = int(0.42 * SAMPLE_RATE)
    grain_plan = ((1, 3, 3.4), (5, 7, 3.3), (9, 11, 3.4), (13, 15, 3.25), (17, 19, 3.3))
    for source_bar, target_bar, target_beat in (
        plan for plan in grain_plan if max(plan[0], plan[1]) < bar_count
    ):
        source_start = int((source_bar * bar_seconds + 0.65) * SAMPLE_RATE) % sample_count
        indices = (source_start + np.arange(grain_length)) % sample_count
        grain = piano[:, indices][:, ::-1].copy()
        window = np.sin(np.linspace(0.0, math.pi, grain_length, endpoint=False)) ** 2
        grain *= window * 0.11
        target = int(round((target_bar * bar_seconds + target_beat * beat_seconds) * SAMPLE_RATE))
        add_loop(machine, grain, target)

    if unhurried:
        # Ease the event layers in and back out across the loop. The bed underneath
        # them never moves, so this reads as the piece gathering rather than fading up.
        arch = opening_arch(total=sample_count, duration=duration)[np.newaxis, :]
        piano = piano * arch
        glass = glass * arch
        machine = machine * arch

    if realistic:
        # One shared room, fed at different send levels so the layers sit at different
        # depths — piano nearest, resonance and memory furthest back. Convolving once
        # on a send bus is both cheaper and more coherent than per-stem echo chains.
        # Decay constants are the exponential tau, so RT60 is about 6.91*tau: roughly
        # 4.3 s low, 3.1 s mid, 1.5 s high. Lows ring longest and highs die first,
        # which is what a real stone space does — and at 64 BPM a ~4 s tail is about
        # one bar, lush without washing the piano away.
        room = synth_reverb_impulse(
            rng,
            seconds=4.2,
            decay_low=0.62,
            decay_mid=0.45,
            decay_high=0.22,
            predelay_seconds=0.021,
        )
        breath_bed = (
            breathing_noise_bed(
                rng,
                amplitude=0.15 if subdued else 0.060,
                total=sample_count,
                duration=duration,
                gentle=flowing,
                delay_bars=0.18 if retimed else (0.30 if flowing else 0.0),
                sailing=retimed,
                cycles=5.0,
            )
            if breathing else 0.0
        )
        send = (
            piano * 0.30
            + pad * 0.34
            + pulse * 0.09
            + glass * 0.40
            + machine * 0.32
            + memory * 0.42
            + resonance * 0.46
            + breath_bed * 0.25
            + toll * 0.38
            + hum * 0.30
        )
        mix = (
            piano + pad + pulse + glass + machine + memory + resonance
            + breath_bed + toll + hum
        )
        mix = mix + circular_convolve(send, room)
        mix = mix + air_bed(rng, amplitude=0.0024, total=sample_count)
    else:
        piano = circular_delay_mix(
            piano,
            [(0.023, 0.075, 0.72), (0.117, 0.055, -0.60), (0.263, 0.040, 0.46),
             (0.469, 0.030, -0.34), (0.733, 0.022, 0.58), (1.087, 0.015, -0.48)],
        )
        glass = circular_delay_mix(
            glass,
            [(0.181, 0.10, -0.52), (0.703, 0.15, 0.58), (1.406, 0.055, -0.44)],
        )
        if introduced_at("v2", arrangement):
            memory = circular_delay_mix(
                memory,
                [(0.337, 0.070, 0.52), (1.117, 0.052, -0.48),
                 (2.341, 0.035, 0.40), (3.703, 0.022, -0.34)],
            )
        if arrangement == "v2":
            glass = circular_delay_mix(
                glass,
                [(1.873, 0.042, 0.46), (3.127, 0.026, -0.40)],
            )
        if introduced_at("v3", arrangement):
            resonance = circular_delay_mix(
                resonance,
                [(0.463, 0.130, 0.42), (1.237, 0.105, -0.40),
                 (2.873, 0.070, 0.34), (4.337, 0.045, -0.30)],
            )

        mix = piano + pad + pulse + glass + machine + memory + resonance + toll + hum
    mix = apply_periodic_wow(mix, sample_count)
    if subdued:
        mix = tame_high_shelf(mix)
    if weighted:
        mix = trim_subsonic_weight(mix)
    if dark:
        mix = loop_edge_fade(mix, seconds=3.0, total=sample_count)

    # Gentle bus glue and conservative prototype mastering.
    mix = np.tanh(mix * 0.92)
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


def write_pcm24_wav(path: Path, signal: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    interleaved = signal.T.reshape(-1)
    integers = np.clip(interleaved, -1.0, 1.0 - 1.0 / 8_388_608.0)
    integers = np.round(integers * 8_388_607.0).astype(np.int32)
    packed = np.empty((integers.size, 3), dtype=np.uint8)
    packed[:, 0] = integers & 0xFF
    packed[:, 1] = (integers >> 8) & 0xFF
    packed[:, 2] = (integers >> 16) & 0xFF
    with wave.open(str(path), "wb") as output:
        output.setnchannels(2)
        output.setsampwidth(3)
        output.setframerate(SAMPLE_RATE)
        output.writeframes(packed.tobytes())


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path, help="Output 24-bit stereo WAV path")
    parser.add_argument(
        "--arrangement",
        choices=ARRANGEMENTS,
        default="v1",
        help="Arrangement to render (default: v1 for backwards reproducibility)",
    )
    arguments = parser.parse_args()
    rendered = render(arguments.arrangement)
    write_pcm24_wav(arguments.output, rendered)
    peak = float(np.max(np.abs(rendered)))
    rms = float(np.sqrt(np.mean(np.square(rendered, dtype=np.float64))))
    print(f"path={arguments.output}")
    print(f"duration_seconds={rendered.shape[1] / SAMPLE_RATE:.3f}")
    print(f"sample_rate={SAMPLE_RATE}")
    print(f"arrangement={arguments.arrangement}")
    print("channels=2")
    print("sample_width_bits=24")
    print(f"peak_dbfs={20.0 * math.log10(max(peak, 1e-12)):.2f}")
    print(f"rms_dbfs={20.0 * math.log10(max(rms, 1e-12)):.2f}")
    print(f"sha256={sha256(arguments.output)}")


if __name__ == "__main__":
    main()
