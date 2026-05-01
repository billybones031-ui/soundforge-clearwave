"""
Pytest suite for audio_processor.py — pure DSP functions only.
No Firebase, no network, no real audio files needed.
"""

import sys
import os
import math
import tempfile
from pathlib import Path

import numpy as np
import pytest
import soundfile as sf

# Allow importing from backend/
sys.path.insert(0, str(Path(__file__).parent.parent))

import audio_processor as ap

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

SR = 44100  # standard sample rate used across tests

def sine(freq=440.0, duration=1.0, sr=SR, amplitude=0.5) -> np.ndarray:
    t = np.linspace(0, duration, int(sr * duration), endpoint=False)
    return (amplitude * np.sin(2 * np.pi * freq * t)).astype(np.float32)

def silence(duration=1.0, sr=SR) -> np.ndarray:
    return np.zeros(int(sr * duration), dtype=np.float32)

def noise(duration=1.0, sr=SR, amplitude=0.01, seed=42) -> np.ndarray:
    rng = np.random.default_rng(seed)
    return (amplitude * rng.standard_normal(int(sr * duration))).astype(np.float32)


# ---------------------------------------------------------------------------
# measure_peak_db
# ---------------------------------------------------------------------------

class TestMeasurePeakDb:
    def test_silence_returns_minus_120(self):
        assert ap.measure_peak_db(silence()) == -120.0

    def test_full_scale_returns_zero(self):
        s = np.array([1.0], dtype=np.float32)
        assert ap.measure_peak_db(s) == pytest.approx(0.0, abs=0.01)

    def test_half_scale_returns_minus_6(self):
        s = np.array([0.5], dtype=np.float32)
        assert ap.measure_peak_db(s) == pytest.approx(-6.02, abs=0.1)

    def test_picks_absolute_maximum(self):
        s = np.array([0.1, -0.9, 0.3], dtype=np.float32)
        expected = 20.0 * math.log10(0.9)
        assert ap.measure_peak_db(s) == pytest.approx(expected, abs=0.01)

    def test_near_zero_returns_minus_120(self):
        s = np.array([1e-8], dtype=np.float32)
        assert ap.measure_peak_db(s) == -120.0


# ---------------------------------------------------------------------------
# estimate_noise_floor_db
# ---------------------------------------------------------------------------

class TestEstimateNoiseFloorDb:
    def test_silence_returns_minus_120(self):
        assert ap.estimate_noise_floor_db(silence(), SR) == -120.0

    def test_shorter_than_one_frame_returns_minus_120(self):
        short = np.zeros(50, dtype=np.float32)   # <128 samples
        assert ap.estimate_noise_floor_db(short, SR) == -120.0

    def test_picks_quiet_region(self):
        # 9 quiet frames + 1 loud frame — floor should reflect the quiet ones
        frame = int(np.clip(SR // 100, 128, 1024))
        quiet = np.full(frame * 9, 0.005, dtype=np.float32)
        loud  = np.full(frame,     0.9,   dtype=np.float32)
        pcm   = np.concatenate([quiet, loud])
        db    = ap.estimate_noise_floor_db(pcm, SR)
        assert db < -30.0, f"Expected floor < -30 dB, got {db:.1f}"

    def test_uniform_signal_gives_consistent_db(self):
        s = np.full(SR, 0.1, dtype=np.float32)
        db = ap.estimate_noise_floor_db(s, SR)
        expected = 20.0 * math.log10(0.1)
        assert db == pytest.approx(expected, abs=1.0)


# ---------------------------------------------------------------------------
# apply_room_correction (HPF)
# ---------------------------------------------------------------------------

class TestApplyRoomCorrection:
    def test_attenuates_dc(self):
        dc = np.ones(SR, dtype=np.float32)
        filtered = ap.apply_room_correction(dc, SR)
        # After settling (skip first 0.1 s), RMS should be near zero
        tail = filtered[SR // 10:]
        rms  = float(np.sqrt(np.mean(tail ** 2)))
        assert rms < 0.01, f"DC not attenuated: RMS={rms:.4f}"

    def test_passes_1khz(self):
        sig    = sine(freq=1000.0, amplitude=0.8)
        rms_in = float(np.sqrt(np.mean(sig ** 2)))
        out    = ap.apply_room_correction(sig, SR)
        # Skip transient at start
        rms_out = float(np.sqrt(np.mean(out[SR // 4:] ** 2)))
        assert rms_out > rms_in * 0.9, (
            f"1 kHz attenuated too much: in={rms_in:.3f} out={rms_out:.3f}"
        )

    def test_output_shape_preserved(self):
        s   = sine()
        out = ap.apply_room_correction(s, SR)
        assert out.shape == s.shape

    def test_output_is_float32(self):
        out = ap.apply_room_correction(sine(), SR)
        assert out.dtype == np.float32

    def test_40hz_attenuated(self):
        # 40 Hz is well below the 80 Hz cutoff — should be attenuated
        sig = sine(freq=40.0, amplitude=0.8)
        out = ap.apply_room_correction(sig, SR)
        rms_in  = float(np.sqrt(np.mean(sig[SR // 4:] ** 2)))
        rms_out = float(np.sqrt(np.mean(out[SR // 4:] ** 2)))
        assert rms_out < rms_in * 0.5, (
            f"40 Hz not attenuated enough: in={rms_in:.3f} out={rms_out:.3f}"
        )


# ---------------------------------------------------------------------------
# normalize_loudness
# ---------------------------------------------------------------------------

class TestNormalizeLoudness:
    def test_silence_unchanged(self):
        s   = silence()
        out = ap.normalize_loudness(s, SR, target_lufs=-14.0)
        assert np.allclose(out, 0.0)

    def test_scales_to_target_lufs(self):
        # -30 dBFS sine → normalise to -14 LUFS
        sig = sine(amplitude=0.03, duration=2.0)   # quiet signal, >=0.4s
        out = ap.normalize_loudness(sig, SR, target_lufs=-14.0)
        rms = float(np.sqrt(np.mean(out ** 2)))
        actual_lufs = 20.0 * math.log10(rms)
        assert actual_lufs == pytest.approx(-14.0, abs=1.5)

    def test_attenuates_loud_signal(self):
        sig = sine(amplitude=0.9, duration=2.0)
        out = ap.normalize_loudness(sig, SR, target_lufs=-20.0)
        rms = float(np.sqrt(np.mean(out ** 2)))
        actual_lufs = 20.0 * math.log10(rms)
        assert actual_lufs == pytest.approx(-20.0, abs=1.5)

    def test_output_clipped_to_minus1_plus1(self):
        # Extremely quiet → very high gain → output must be clipped
        sig = sine(amplitude=1e-4, duration=2.0)
        out = ap.normalize_loudness(sig, SR, target_lufs=-6.0)
        assert float(np.max(np.abs(out))) <= 1.0

    def test_short_clip_fallback(self):
        # < 0.4 s falls back to RMS normalize — should not raise
        short = sine(amplitude=0.02, duration=0.2)
        out   = ap.normalize_loudness(short, SR, target_lufs=-14.0)
        assert out.shape == short.shape
        assert out.dtype == np.float32


# ---------------------------------------------------------------------------
# apply_noise_reduction
# ---------------------------------------------------------------------------

class TestApplyNoiseReduction:
    def test_output_shape_preserved(self):
        sig = sine() + noise()
        out = ap.apply_noise_reduction(sig, SR, threshold=0.3)
        assert out.shape == sig.shape

    def test_output_is_float32(self):
        out = ap.apply_noise_reduction(sine() + noise(), SR)
        assert out.dtype == np.float32

    def test_silence_stays_near_zero(self):
        out = ap.apply_noise_reduction(silence(), SR, threshold=0.3)
        assert float(np.max(np.abs(out))) < 0.01

    def test_reduces_noise_energy(self):
        n   = noise(amplitude=0.1)
        out = ap.apply_noise_reduction(n, SR, threshold=0.8)
        rms_in  = float(np.sqrt(np.mean(n ** 2)))
        rms_out = float(np.sqrt(np.mean(out ** 2)))
        assert rms_out < rms_in, (
            f"Noise not reduced: in={rms_in:.4f} out={rms_out:.4f}"
        )

    def test_short_clip_stationary_mode_no_crash(self):
        # Clip shorter than 0.5 s → stationary mode path
        short = noise(duration=0.2, amplitude=0.05)
        out   = ap.apply_noise_reduction(short, SR, threshold=0.5)
        assert out.shape == short.shape

    def test_threshold_clamp_extremes(self):
        sig = sine(amplitude=0.5) + noise(amplitude=0.05)
        # Should not raise for out-of-range threshold values
        ap.apply_noise_reduction(sig, SR, threshold=-5.0)
        ap.apply_noise_reduction(sig, SR, threshold=100.0)


# ---------------------------------------------------------------------------
# process_file (integration — uses real WAV I/O, no Firebase)
# ---------------------------------------------------------------------------

class TestProcessFile:
    """
    Integration tests for the full pipeline.  Use WAV output so these tests
    run without ffmpeg (Dockerfile installs ffmpeg for production M4A output).
    """

    def _write_wav(self, path: Path, samples: np.ndarray, sr: int = SR):
        sf.write(str(path), samples, sr, subtype="PCM_16")

    def test_round_trip_produces_output_file(self, tmp_path):
        src = tmp_path / "input.wav"
        dst = tmp_path / "output.wav"
        self._write_wav(src, sine(duration=2.0))
        result = ap.process_file(src, dst)
        assert dst.exists()
        assert dst.stat().st_size > 0
        assert result.output_path == dst

    def test_result_metrics_in_valid_range(self, tmp_path):
        src = tmp_path / "input.wav"
        dst = tmp_path / "output.wav"
        self._write_wav(src, sine(amplitude=0.5, duration=2.0))
        result = ap.process_file(src, dst)
        assert -120.0 <= result.input_peak_db <= 0.0
        assert -120.0 <= result.output_peak_db <= 0.0
        assert -120.0 <= result.noise_floor_db <= 0.0
        assert result.duration_ms > 0

    def test_all_options_disabled(self, tmp_path):
        src = tmp_path / "input.wav"
        dst = tmp_path / "output.wav"
        self._write_wav(src, sine(duration=2.0))
        opts = ap.ProcessingOptions(
            noise_reduction=False,
            room_correction=False,
            normalization=False,
        )
        result = ap.process_file(src, dst, opts)
        assert dst.exists()

    def test_progress_callback_called(self, tmp_path):
        src = tmp_path / "input.wav"
        dst = tmp_path / "output.wav"
        self._write_wav(src, sine(duration=2.0))
        calls = []
        ap.process_file(src, dst, on_progress=calls.append)
        assert len(calls) > 0
        assert calls[-1] == pytest.approx(1.0, abs=0.01)
        assert all(0.0 <= p <= 1.0 for p in calls)
