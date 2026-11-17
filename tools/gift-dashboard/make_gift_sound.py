#!/usr/bin/env python3
"""Synthesise a gift's own sound effect.

The dashboard calls this so every gift ships with a sound. A seeded
pentatonic pitch cluster gives the gift its identity; the tier picks the
flourish (blip / sparkle arpeggio / rising sweep / big hit), so a cheap
gift reads as a small chime and a legendary one as a multi-layer hit.

The output matches the app's bundled `res/raw/gift_*.wav` format exactly
(mono, 22.05 kHz, 16-bit) so a generated clip and a hand-authored one are
interchangeable. When the uploaded clip carries its own soundtrack,
`extract_audio` in server.py replaces this file — the operator's sound
wins.

Usage:
    python3 make_gift_sound.py --id <gift-id> --tier <1..4> -o out.wav
"""

from __future__ import annotations

import argparse
import hashlib
import wave
from pathlib import Path

import numpy as np

RATE = 22050

# One octave of a major pentatonic scale, rooted at C5. Consonant in any
# subset, so a seed-chosen cluster never clashes.
PENTATONIC = [523.25, 587.33, 659.25, 783.99, 880.00, 1046.50, 1174.66, 1318.51]

# tier -> (duration seconds, flourish). Durations mirror the bundled clips
# (0.57 / 0.77 / 1.10 / 1.40 s) so a generated sound feels the same size.
TIERS = {
    1: (0.57, "blip"),
    2: (0.77, "sparkle"),
    3: (1.10, "sweep"),
    4: (1.40, "hit"),
}


def seed_of(gift_id: str) -> int:
    return int(hashlib.sha256(gift_id.encode("utf-8")).hexdigest(), 16)


def note(
    freq: float,
    dur: float,
    amp: float,
    decay: float,
    partials: tuple[float, ...] = (1.0, 2.0, 3.0),
    pamps: tuple[float, ...] = (1.0, 0.28, 0.12),
    rate: int = RATE,
) -> np.ndarray:
    """A single plucked tone: fast attack, exponential decay, a partial stack."""
    n = max(1, int(dur * rate))
    t = np.arange(n) / rate
    env = (1.0 - np.exp(-t / 0.004)) * np.exp(-t / decay)
    sig = np.zeros(n)
    for mult, pa in zip(partials, pamps):
        sig += pa * np.sin(2 * np.pi * freq * mult * t)
    return amp * env * sig


def sweep(f0: float, f1: float, dur: float, amp: float, rate: int = RATE) -> np.ndarray:
    """A rising glide with a slow shimmer envelope."""
    n = max(1, int(dur * rate))
    t = np.arange(n) / rate
    k = (f1 / f0) ** (1.0 / max(dur, 1e-6))
    phase = 2 * np.pi * f0 * (k**t - 1.0) / np.log(k)
    env = (1.0 - np.exp(-t / 0.01)) * np.exp(-t / (dur * 0.7))
    shimmer = 0.15 * np.sin(2 * np.pi * 6.0 * t)
    return amp * env * (1.0 + shimmer) * np.sin(phase)


def mix_into(buf: np.ndarray, sig: np.ndarray, at: int) -> None:
    end = min(len(buf), at + len(sig))
    if end > at:
        buf[at:end] += sig[: end - at]


def synth(gift_id: str, tier: int) -> np.ndarray:
    dur, flourish = TIERS.get(tier, TIERS[1])
    rng = np.random.default_rng(seed_of(gift_id) & 0xFFFFFFFF)
    buf = np.zeros(int(dur * RATE) + RATE // 4, dtype=np.float64)

    root = PENTATONIC[int(rng.integers(0, 5))]
    octave = 2.0 if rng.random() < 0.35 else 1.0
    base = root * octave
    # Per-gift timbre so two gifts with the same pitch cluster still differ.
    partials = (1.0, 2.0 + float(rng.uniform(-0.05, 0.12)), 3.0 + float(rng.uniform(-0.1, 0.2)))
    pamps = (1.0, float(rng.uniform(0.15, 0.4)), float(rng.uniform(0.05, 0.2)))

    if flourish == "blip":
        mix_into(buf, note(base, dur, 0.65, dur * 0.45, partials, pamps), 0)
    elif flourish == "sparkle":
        steps = [0, 2, 4]
        for i, s in enumerate(steps):
            f = PENTATONIC[min(s, len(PENTATONIC) - 1)] * octave
            mix_into(buf, note(f, dur * 0.5, 0.5, dur * 0.35, partials, pamps), int(i * 0.12 * RATE))
        mix_into(buf, note(base * 2.0, dur * 0.4, 0.25, dur * 0.25, partials, pamps), int(0.24 * RATE))
    elif flourish == "sweep":
        mix_into(buf, note(base, dur * 0.6, 0.45, dur * 0.35, partials, pamps), 0)
        mix_into(buf, sweep(base * 0.6, base * 1.6, dur * 0.8, 0.45), int(0.08 * RATE))
    else:  # hit
        mix_into(buf, note(base / 2.0, dur * 0.9, 0.5, dur * 0.5, partials, pamps), 0)
        mix_into(buf, note(base, dur, 0.5, dur * 0.45, partials, pamps), 0)
        mix_into(buf, sweep(base * 0.8, base * 1.8, dur * 0.7, 0.35), int(0.05 * RATE))
        for i, s in enumerate([4, 6]):
            f = PENTATONIC[min(s, len(PENTATONIC) - 1)] * octave
            mix_into(buf, note(f, dur * 0.35, 0.2, dur * 0.25, partials, pamps), int((0.18 + 0.12 * i) * RATE))

    buf = buf[: int(dur * RATE)]
    peak = float(np.max(np.abs(buf))) or 1.0
    buf = buf / peak * 0.92
    return buf


def write_wav(path: Path, samples: np.ndarray, rate: int = RATE) -> None:
    pcm = np.clip(samples, -1.0, 1.0)
    pcm = (pcm * 32767.0).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--id", required=True, help="gift id (seeds the pitch cluster)")
    ap.add_argument("--tier", type=int, default=1, help="1..4, picks the flourish")
    ap.add_argument("-o", "--output", required=True, help="output .wav path")
    args = ap.parse_args()

    write_wav(Path(args.output), synth(args.id, args.tier))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
