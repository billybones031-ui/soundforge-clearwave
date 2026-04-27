package com.isl.soundforge

/**
 * Pure-JVM extraction of the DSP math from AudioProcessor.kt.
 * No Android API dependencies — these functions can be unit-tested on any JVM.
 */

fun rmsOf(pcm: FloatArray, start: Int, length: Int): Float {
    if (length == 0) return 0f
    var sum = 0.0
    for (i in start until start + length) {
        val s = pcm[i].toDouble()
        sum += s * s
    }
    return Math.sqrt(sum / length).toFloat()
}

fun estimateNoiseFloor(pcm: FloatArray, frameSize: Int): Float {
    val frameRms = mutableListOf<Float>()
    var i = 0
    while (i + frameSize <= pcm.size) {
        frameRms.add(rmsOf(pcm, i, frameSize))
        i += frameSize
    }
    if (frameRms.isEmpty()) return 0f
    frameRms.sort()
    return frameRms[maxOf(0, frameRms.size / 10)]
}

fun measurePeakDb(pcm: ShortArray): Float {
    var peak = 0f
    for (s in pcm) {
        val abs = Math.abs(s / 32768f)
        if (abs > peak) peak = abs
    }
    return if (peak < 1e-6f) -120f
    else (20f * Math.log10(peak.toDouble())).toFloat()
}

fun estimateNoiseFloorDb(pcm: ShortArray): Float {
    val floats = FloatArray(pcm.size) { pcm[it] / 32768f }
    val floor = estimateNoiseFloor(floats, 512)
    return if (floor < 1e-6f) -120f
    else (20f * Math.log10(floor.toDouble())).toFloat()
}

/**
 * Applies a gentle high-pass filter to reduce low-frequency room resonance.
 * Single-pole IIR at ~80 Hz: α = e^(-2π * fc / fs)
 */
fun applyRoomCorrection(pcm: FloatArray, sampleRate: Int) {
    val fc = 80f
    val alpha = Math.exp((-2.0 * Math.PI * fc / sampleRate)).toFloat()
    var prev = 0f
    var prevIn = 0f
    for (i in pcm.indices) {
        val input = pcm[i]
        pcm[i] = alpha * (prev + input - prevIn)
        prev = pcm[i]
        prevIn = input
    }
}

/**
 * Normalizes loudness toward [targetLUFS] using RMS-based gain.
 */
fun normalizeLoudness(pcm: FloatArray, targetLUFS: Float) {
    val rms = rmsOf(pcm, 0, pcm.size)
    if (rms < 1e-6f) return
    val currentLUFS = 20f * Math.log10(rms.toDouble()).toFloat()
    val gainDb = targetLUFS - currentLUFS
    val gainLinear = Math.pow(10.0, (gainDb / 20.0)).toFloat()
    for (i in pcm.indices) {
        pcm[i] *= gainLinear
    }
}

/**
 * Simple spectral noise suppression via soft-knee noise gate.
 * Frame size is ~10 ms derived from [sampleRate], clamped to [128, 1024].
 */
fun applySpectralNoiseSuppression(pcm: FloatArray, sampleRate: Int, threshold: Float) {
    val frameSize = (sampleRate / 100).coerceIn(128, 1024)
    val noiseFloor = estimateNoiseFloor(pcm, frameSize)
    val gateThreshold = noiseFloor * (1f + threshold * 4f)

    var i = 0
    while (i + frameSize <= pcm.size) {
        val rms = rmsOf(pcm, i, frameSize)
        if (rms < gateThreshold) {
            val gain = (rms / gateThreshold).coerceIn(0.05f, 1f)
            for (j in i until i + frameSize) {
                pcm[j] *= gain
            }
        }
        i += frameSize
    }
}
