package com.isl.soundforge

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlin.math.*

class DspMathTest {

    // -------------------------------------------------------------------------
    // rmsOf
    // -------------------------------------------------------------------------

    @Test fun `rmsOf empty length returns zero`() {
        val pcm = floatArrayOf(0.5f, 0.5f)
        assertEquals(0f, rmsOf(pcm, 0, 0))
    }

    @Test fun `rmsOf constant signal equals that value`() {
        // All 1.0 → RMS = 1.0
        val pcm = FloatArray(1024) { 1f }
        assertEquals(1f, rmsOf(pcm, 0, pcm.size), 1e-5f)
    }

    @Test fun `rmsOf full-scale sine is 1 over root 2`() {
        val N = 44100
        val pcm = FloatArray(N) { sin(2.0 * PI * 440.0 * it / N).toFloat() }
        val expected = (1.0 / sqrt(2.0)).toFloat()
        assertEquals(expected, rmsOf(pcm, 0, N), 1e-3f)
    }

    @Test fun `rmsOf sub-range honours start and length`() {
        // First half silence, second half full scale
        val pcm = FloatArray(1024) { if (it < 512) 0f else 1f }
        assertEquals(0f,  rmsOf(pcm, 0, 512),   1e-6f)
        assertEquals(1f,  rmsOf(pcm, 512, 512),  1e-6f)
    }

    @Test fun `rmsOf single sample`() {
        val pcm = floatArrayOf(0.707f)
        assertEquals(0.707f, rmsOf(pcm, 0, 1), 1e-4f)
    }

    // -------------------------------------------------------------------------
    // estimateNoiseFloor
    // -------------------------------------------------------------------------

    @Test fun `estimateNoiseFloor returns zero for empty array`() {
        assertEquals(0f, estimateNoiseFloor(FloatArray(0), 512))
    }

    @Test fun `estimateNoiseFloor returns zero for array shorter than one frame`() {
        val pcm = FloatArray(256) { 0.5f }
        assertEquals(0f, estimateNoiseFloor(pcm, 512))
    }

    @Test fun `estimateNoiseFloor picks low-energy region`() {
        // 9 quiet frames (amplitude 0.01) + 1 loud frame (amplitude 0.9)
        val frameSize = 512
        val pcm = FloatArray(frameSize * 10)
        // Frames 0-8: quiet
        for (i in 0 until frameSize * 9) pcm[i] = 0.01f
        // Frame 9: loud
        for (i in frameSize * 9 until frameSize * 10) pcm[i] = 0.9f

        val noiseFloor = estimateNoiseFloor(pcm, frameSize)
        // Should pick the 10th percentile frame, which is one of the quiet ones
        assertTrue(noiseFloor < 0.02f,
            "Expected noise floor < 0.02 but was $noiseFloor")
    }

    @Test fun `estimateNoiseFloor uniform signal returns that rms`() {
        val frameSize = 512
        val pcm = FloatArray(frameSize * 20) { 0.5f }
        val noiseFloor = estimateNoiseFloor(pcm, frameSize)
        // Every frame has the same RMS ≈ 0.5; 10th percentile == 0.5
        assertEquals(0.5f, noiseFloor, 0.01f)
    }

    // -------------------------------------------------------------------------
    // measurePeakDb
    // -------------------------------------------------------------------------

    @Test fun `measurePeakDb silence returns -120 dBFS`() {
        val pcm = ShortArray(1024) { 0 }
        assertEquals(-120f, measurePeakDb(pcm))
    }

    @Test fun `measurePeakDb full scale returns 0 dBFS`() {
        val pcm = ShortArray(1) { Short.MAX_VALUE }
        val db = measurePeakDb(pcm)
        // Short.MAX_VALUE / 32768 ≈ 0.99997 → ~-0.0003 dBFS
        assertEquals(0f, db, 0.01f)
    }

    @Test fun `measurePeakDb half scale returns -6 dBFS`() {
        // 0.5 linear → 20*log10(0.5) ≈ -6.02 dB
        val halfScale = (0.5f * 32768).toInt().toShort()
        val pcm = ShortArray(1) { halfScale }
        val db = measurePeakDb(pcm)
        assertEquals(-6.02f, db, 0.1f)
    }

    @Test fun `measurePeakDb picks absolute maximum`() {
        val pcm = ShortArray(4) { i ->
            when (i) {
                0 -> 1000
                1 -> -30000
                2 -> 500
                3 -> 100
                else -> 0
            }.toShort()
        }
        val expectedDb = (20.0 * log10(30000.0 / 32768.0)).toFloat()
        assertEquals(expectedDb, measurePeakDb(pcm), 0.01f)
    }

    // -------------------------------------------------------------------------
    // normalizeLoudness
    // -------------------------------------------------------------------------

    @Test fun `normalizeLoudness scales signal to target LUFS`() {
        val targetLUFS = -14f
        val N = 44100
        // Start with a quiet signal at about -30 dBFS
        val pcm = FloatArray(N) { sin(2.0 * PI * 440.0 * it / N).toFloat() * 0.03f }

        normalizeLoudness(pcm, targetLUFS)

        val rms = rmsOf(pcm, 0, N)
        val actualLUFS = 20f * log10(rms.toDouble()).toFloat()
        assertEquals(targetLUFS, actualLUFS, 0.5f)
    }

    @Test fun `normalizeLoudness does not change silence`() {
        val pcm = FloatArray(1024) { 0f }
        normalizeLoudness(pcm, -14f)
        assertTrue(pcm.all { it == 0f }, "Silence should remain unchanged")
    }

    @Test fun `normalizeLoudness can attenuate loud signals`() {
        val targetLUFS = -20f
        val N = 44100
        // Loud signal at ~0 dBFS
        val pcm = FloatArray(N) { sin(2.0 * PI * 440.0 * it / N).toFloat() }

        normalizeLoudness(pcm, targetLUFS)

        val rms = rmsOf(pcm, 0, N)
        val actualLUFS = 20f * log10(rms.toDouble()).toFloat()
        assertEquals(targetLUFS, actualLUFS, 0.5f)
    }

    // -------------------------------------------------------------------------
    // applyRoomCorrection (HPF)
    // -------------------------------------------------------------------------

    @Test fun `applyRoomCorrection removes DC offset`() {
        // A DC signal (constant offset) should be attenuated heavily by an HPF
        val sampleRate = 44100
        val pcm = FloatArray(44100) { 1.0f }  // pure DC

        applyRoomCorrection(pcm, sampleRate)

        // After 44100 samples the IIR filter should have settled; the latter
        // half should be near zero
        val rmsLatter = rmsOf(pcm, 22050, 22050)
        assertTrue(rmsLatter < 0.01f,
            "DC should be attenuated to near zero; got RMS = $rmsLatter")
    }

    @Test fun `applyRoomCorrection passes high frequency signal`() {
        // 1 kHz tone should pass through an 80 Hz HPF largely unattenuated
        val sampleRate = 44100
        val N = 44100
        val pcm = FloatArray(N) { sin(2.0 * PI * 1000.0 * it / N).toFloat() }
        val rmsIn = rmsOf(pcm.copyOf(), 0, N)

        applyRoomCorrection(pcm, sampleRate)

        val rmsOut = rmsOf(pcm, N / 4, N / 2)  // skip transient at start
        // Should retain at least 90% of RMS energy
        assertTrue(rmsOut > rmsIn * 0.9f,
            "1 kHz tone should pass HPF; in=$rmsIn out=$rmsOut")
    }

    // -------------------------------------------------------------------------
    // applySpectralNoiseSuppression
    // -------------------------------------------------------------------------

    @Test fun `applySpectralNoiseSuppression attenuates noise-floor frames`() {
        val sampleRate = 44100
        val frameSize = 512

        // Build: 8 noisy frames (amplitude 0.005) + 4 signal frames (amplitude 0.5)
        val totalSamples = frameSize * 12
        val pcm = FloatArray(totalSamples) { i ->
            if (i < frameSize * 8) 0.005f else 0.5f
        }

        val noisyRmsBefore = rmsOf(pcm, 0, frameSize)
        applySpectralNoiseSuppression(pcm, sampleRate, 0.3f)
        val noisyRmsAfter = rmsOf(pcm, 0, frameSize)
        val signalRmsAfter = rmsOf(pcm, frameSize * 8, frameSize)

        assertTrue(noisyRmsAfter < noisyRmsBefore * 0.5f,
            "Noise frames should be attenuated; before=$noisyRmsBefore after=$noisyRmsAfter")

        // Signal frames should be largely unaffected (they're above the gate)
        assertTrue(signalRmsAfter > 0.4f,
            "Signal frames above threshold should be unaffected; rms=$signalRmsAfter")
    }

    @Test fun `applySpectralNoiseSuppression leaves silence array intact`() {
        val pcm = FloatArray(4096) { 0f }
        applySpectralNoiseSuppression(pcm, 44100, 0.3f)
        assertTrue(pcm.all { it == 0f }, "All-zero array should stay zero")
    }

    @Test fun `applySpectralNoiseSuppression short array no crash`() {
        // Shorter than one frame — should do nothing, not crash
        val pcm = FloatArray(100) { 0.1f }
        applySpectralNoiseSuppression(pcm, 44100, 0.3f)
        // passes if no exception thrown
    }

    // -------------------------------------------------------------------------
    // estimateNoiseFloorDb
    // -------------------------------------------------------------------------

    @Test fun `estimateNoiseFloorDb silence returns -120`() {
        val pcm = ShortArray(4096) { 0 }
        assertEquals(-120f, estimateNoiseFloorDb(pcm))
    }

    @Test fun `estimateNoiseFloorDb returns negative dB for real audio`() {
        // Constant amplitude at 10% of full scale
        val amplitude = (0.1f * 32767).toInt().toShort()
        val pcm = ShortArray(4096) { amplitude }
        val db = estimateNoiseFloorDb(pcm)
        // 20*log10(0.1) ≈ -20 dB
        assertEquals(-20f, db, 1.0f)
    }
}
