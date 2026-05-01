"""
SoundForge ClearWave — Python audio processing pipeline.

Mirrors the DSP chain in AudioProcessor.kt so on-device and cloud
results are perceptually consistent:

  1. Decode input (any format via pydub/ffmpeg) → float32 PCM
  2. Noise reduction (noisereduce spectral subtraction)
  3. Room correction (80 Hz single-pole IIR HPF, matching Android)
  4. Loudness normalisation (BS.1770 via pyloudnorm)
  5. Clip guard (hard limit at 0 dBFS)
  6. Encode → AAC/M4A output via pydub/ffmpeg
"""

from __future__ import annotations

import io
import logging
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

import numpy as np
import pyloudnorm as pyln
import noisereduce as nr
import soundfile as sf
from pydub import AudioSegment
from scipy.signal import lfilter

log = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

@dataclass
class ProcessingOptions:
    noise_reduction: bool = True
    room_correction: bool = True
    normalization: bool = True
    target_lufs: float = -14.0
    noise_threshold: float = 0.3   # 0.0–1.0 attenuation strength


@dataclass
class ProcessingResult:
    output_path: Path
    input_peak_db: float
    output_peak_db: float
    noise_floor_db: float
    duration_ms: int


def process_file(
    input_path: Path,
    output_path: Path,
    options: Optional[ProcessingOptions] = None,
    on_progress=None,
) -> ProcessingResult:
    """
    Full pipeline: decode → DSP → encode.

    [on_progress] is an optional callable(float 0–1) for progress reporting.
    """
    if options is None:
        options = ProcessingOptions()

    def progress(pct: float):
        if on_progress:
            on_progress(pct)

    # 1. Decode to float32 PCM
    progress(0.05)
    samples, sr = _decode(input_path)
    log.info("Decoded %s: %d samples @ %d Hz (%.1fs)",
             input_path.name, len(samples), sr, len(samples) / sr)
    progress(0.2)

    # 2. Measure input
    input_peak = measure_peak_db(samples)
    noise_floor = estimate_noise_floor_db(samples, sr)
    progress(0.25)

    # 3. DSP chain
    if options.noise_reduction:
        samples = apply_noise_reduction(samples, sr, options.noise_threshold)
        progress(0.5)

    if options.room_correction:
        samples = apply_room_correction(samples, sr)
        progress(0.65)

    if options.normalization:
        samples = normalize_loudness(samples, sr, options.target_lufs)
        progress(0.8)

    # 4. Clip guard
    samples = np.clip(samples, -1.0, 1.0)
    output_peak = measure_peak_db(samples)
    progress(0.85)

    # 5. Encode
    _encode(samples, sr, output_path)
    progress(1.0)

    duration_ms = int(len(samples) / sr * 1000)
    log.info("Wrote %s | in=%.1f dBFS out=%.1f dBFS noise=%.1f dBFS",
             output_path.name, input_peak, output_peak, noise_floor)

    return ProcessingResult(
        output_path=output_path,
        input_peak_db=float(input_peak),
        output_peak_db=float(output_peak),
        noise_floor_db=float(noise_floor),
        duration_ms=duration_ms,
    )


# ---------------------------------------------------------------------------
# DSP functions (pure numpy / scipy — no I/O, fully unit-testable)
# ---------------------------------------------------------------------------

def apply_noise_reduction(
    samples: np.ndarray,
    sr: int,
    threshold: float = 0.3,
) -> np.ndarray:
    """
    Spectral noise suppression via noisereduce.

    Uses the quietest 0.5 s at the start as the noise profile estimate
    (common for podcasts/streams where the first half-second is room tone
    before the speaker starts). Falls back to stationary mode if the clip
    is shorter than 0.5 s.

    [threshold] maps to prop_decrease (0 = no reduction, 1 = full wipe).
    Clamped to [0.05, 0.95] to avoid both artefacts and over-suppression.
    """
    # Near-silence: noisereduce would divide by zero → NaN. Return as-is.
    if float(np.max(np.abs(samples))) < 1e-6:
        return samples.copy()

    prop = float(np.clip(threshold, 0.05, 0.95))
    noise_len = int(sr * 0.5)
    stationary = len(samples) <= noise_len

    if stationary:
        reduced = nr.reduce_noise(y=samples, sr=sr,
                                  prop_decrease=prop, stationary=True)
    else:
        noise_clip = samples[:noise_len]
        reduced = nr.reduce_noise(y=samples, sr=sr, y_noise=noise_clip,
                                  prop_decrease=prop, stationary=False)
    return reduced.astype(np.float32)


def apply_room_correction(samples: np.ndarray, sr: int) -> np.ndarray:
    """
    Single-pole IIR high-pass filter at 80 Hz.

    Transfer function matches Android's implementation exactly:
      H(z) = alpha * (1 - z⁻¹) / (1 - alpha·z⁻¹)
      alpha = e^(-2π·fc/fs)

    scipy.signal.lfilter gives the same result as the Android sample loop
    but runs in compiled C instead of a Python for-loop.
    """
    fc = 80.0
    alpha = float(np.exp(-2.0 * np.pi * fc / sr))
    b = np.array([alpha, -alpha], dtype=np.float64)
    a = np.array([1.0, -alpha], dtype=np.float64)
    return lfilter(b, a, samples.astype(np.float64)).astype(np.float32)


def normalize_loudness(
    samples: np.ndarray,
    sr: int,
    target_lufs: float = -14.0,
) -> np.ndarray:
    """
    Integrated loudness normalization to [target_lufs] using BS.1770-4.

    pyloudnorm requires at least 0.4 s of audio to measure integrated
    loudness. Falls back to RMS gain (same as Android) for very short clips.
    """
    if len(samples) / sr < 0.4:
        return _rms_normalize(samples, target_lufs)

    meter = pyln.Meter(sr)
    # pyloudnorm wants shape (n_samples,) for mono or (n_samples, n_channels)
    audio_2d = samples[:, np.newaxis] if samples.ndim == 1 else samples
    loudness = meter.integrated_loudness(audio_2d)

    if not np.isfinite(loudness):  # silence or near-silence
        return samples

    normalized = pyln.normalize.loudness(audio_2d, loudness, target_lufs)
    result = normalized[:, 0] if normalized.ndim > 1 else normalized
    return np.clip(result, -1.0, 1.0).astype(np.float32)


def measure_peak_db(samples: np.ndarray) -> float:
    """Peak level in dBFS. Returns -120.0 for silence."""
    peak = float(np.max(np.abs(samples)))
    if peak < 1e-6:
        return -120.0
    return float(20.0 * np.log10(peak))


def estimate_noise_floor_db(samples: np.ndarray, sr: int) -> float:
    """
    10th-percentile frame RMS in dBFS — mirrors Android's estimateNoiseFloor.
    Frame size ≈ 10 ms, clamped to [128, 1024] samples.
    """
    frame_size = int(np.clip(sr // 100, 128, 1024))
    n_frames = len(samples) // frame_size
    if n_frames == 0:
        return -120.0

    frames = samples[:n_frames * frame_size].reshape(n_frames, frame_size)
    rms_values = np.sqrt(np.mean(frames ** 2, axis=1))
    rms_values.sort()
    floor = float(rms_values[max(0, n_frames // 10)])

    if floor < 1e-6:
        return -120.0
    return float(20.0 * np.log10(floor))


# ---------------------------------------------------------------------------
# I/O helpers
# ---------------------------------------------------------------------------

def _decode(path: Path) -> tuple[np.ndarray, int]:
    """
    Decode any audio format → float32 mono/stereo numpy array.

    Tries soundfile first (fast, handles WAV/FLAC/OGG/AIFF natively).
    Falls back to pydub+ffmpeg for compressed formats (MP3/M4A/AAC/OPUS).
    Always returns float32 in range [-1, 1].
    """
    try:
        samples, sr = sf.read(str(path), dtype="float32", always_2d=False)
        return samples, sr
    except Exception:
        pass  # not a soundfile-native format, fall through to pydub

    seg = AudioSegment.from_file(str(path))
    sr = seg.frame_rate
    raw = np.array(seg.get_array_of_samples(), dtype=np.float32)

    if seg.channels == 2:
        raw = raw.reshape(-1, 2)

    # Normalise from integer range to [-1, 1]
    bits = seg.sample_width * 8
    raw /= float(2 ** (bits - 1))
    return raw, sr


def _encode(samples: np.ndarray, sr: int, output_path: Path) -> None:
    """
    Encode float32 PCM to the format implied by output_path's extension.

    WAV/FLAC/OGG → soundfile (no external dependency).
    M4A/AAC/MP3   → soundfile WAV intermediate + pydub/ffmpeg.
    """
    suffix = output_path.suffix.lower()
    if suffix in (".wav", ".flac", ".ogg", ".aiff"):
        subtype = "PCM_16" if suffix in (".wav", ".aiff") else None
        sf.write(str(output_path), samples, sr, subtype=subtype)
        return

    # Compressed formats need ffmpeg via pydub
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        tmp_path = Path(tmp.name)

    try:
        sf.write(str(tmp_path), samples, sr, subtype="PCM_16")
        seg = AudioSegment.from_wav(str(tmp_path))
        seg.export(
            str(output_path),
            format="ipod",      # pydub's name for M4A/AAC container
            codec="aac",
            bitrate="192k",
        )
    finally:
        tmp_path.unlink(missing_ok=True)


def _rms_normalize(samples: np.ndarray, target_lufs: float) -> np.ndarray:
    """RMS-based gain fallback for clips shorter than 0.4 s."""
    rms = float(np.sqrt(np.mean(samples ** 2)))
    if rms < 1e-6:
        return samples
    current_lufs = 20.0 * np.log10(rms)
    gain_db = target_lufs - current_lufs
    gain = float(10.0 ** (gain_db / 20.0))
    return np.clip(samples * gain, -1.0, 1.0).astype(np.float32)
