package com.isl.soundforge.ai

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.isl.soundforge.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Calls the Gemini API to get intelligent audio processing recommendations.
 *
 * We send a structured description of the audio's measured characteristics
 * (noise floor, peak level, duration, recording environment hints) and ask
 * Gemini to recommend DSP settings + explain what it found.
 *
 * This is text-only inference — we're not sending audio bytes to the API.
 * The on-device [AudioProcessor] or Python backend does the actual DSP.
 */
class GeminiAudioAnalyzer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    data class AudioCharacteristics(
        val durationMs: Long,
        val peakDb: Float,
        val rmsDb: Float,
        val noiseFloorDb: Float,
        val dynamicRangeDb: Float,
        val fileName: String
    )

    data class AnalysisResult(
        val recommendation: String,
        val noiseReduction: Boolean,
        val noiseThreshold: Float,
        val roomCorrection: Boolean,
        val normalization: Boolean,
        val targetLUFS: Float,
        val explanation: String
    )

    /**
     * Sends the measured audio characteristics to Gemini and returns
     * recommended processing settings.
     *
     * [runtimeKey] is the key entered by the user at runtime (from Settings).
     * Falls back to the build-time key from local.properties if [runtimeKey]
     * is blank. Returns null if neither is configured (graceful degradation —
     * the app still works without AI recommendations).
     */
    suspend fun analyze(chars: AudioCharacteristics, runtimeKey: String = ""): AnalysisResult? {
        val apiKey = runtimeKey.ifBlank { BuildConfig.GEMINI_API_KEY }
        if (apiKey.isBlank()) return null

        return withContext(Dispatchers.IO) {
            val prompt = buildPrompt(chars)
            val response = callGemini(apiKey, prompt) ?: return@withContext null
            parseResponse(response)
        }
    }

    // -------------------------------------------------------------------------
    // Private
    // -------------------------------------------------------------------------

    private fun buildPrompt(chars: AudioCharacteristics): String = """
        You are an expert audio engineer analyzing audio for a content creator.

        Measured audio characteristics:
        - Duration: ${chars.durationMs / 1000}s
        - Peak level: ${chars.peakDb.format(1)} dBFS
        - RMS level: ${chars.rmsDb.format(1)} dBFS
        - Noise floor: ${chars.noiseFloorDb.format(1)} dBFS
        - Dynamic range: ${chars.dynamicRangeDb.format(1)} dB
        - File: ${chars.fileName}

        Based on these measurements, provide audio processing recommendations.

        Respond with ONLY valid JSON in this exact format:
        {
          "recommendation": "one-sentence summary",
          "noiseReduction": true or false,
          "noiseThreshold": 0.0 to 1.0,
          "roomCorrection": true or false,
          "normalization": true or false,
          "targetLUFS": -23.0 to -9.0,
          "explanation": "2-3 sentence explanation of what was found and why these settings"
        }
    """.trimIndent()

    private fun callGemini(apiKey: String, prompt: String): String? {
        val body = gson.toJson(
            GeminiRequest(
                contents = listOf(
                    Content(parts = listOf(Part(text = prompt)))
                )
            )
        )

        val request = Request.Builder()
            .url("$BASE_URL?key=$apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null

        val responseBody = response.body?.string() ?: return null
        val parsed = gson.fromJson(responseBody, GeminiResponse::class.java)
        return parsed.candidates?.firstOrNull()
            ?.content?.parts?.firstOrNull()?.text
    }

    private fun parseResponse(text: String): AnalysisResult? {
        return try {
            // Extract the JSON block (Gemini sometimes wraps it in markdown)
            val json = text
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            gson.fromJson(json, AnalysisResult::class.java)
        } catch (_: Exception) {
            // If parsing fails, return safe defaults with the raw text as explanation
            AnalysisResult(
                recommendation = "Apply standard cleanup settings",
                noiseReduction = true,
                noiseThreshold = 0.3f,
                roomCorrection = true,
                normalization = true,
                targetLUFS = -14f,
                explanation = text.take(200)
            )
        }
    }

    private fun Float.format(decimals: Int) = "%.${decimals}f".format(this)

    // -------------------------------------------------------------------------
    // Gemini REST API data classes
    // -------------------------------------------------------------------------

    private data class GeminiRequest(
        val contents: List<Content>,
        @SerializedName("generationConfig")
        val generationConfig: GenerationConfig = GenerationConfig()
    )

    private data class Content(val parts: List<Part>, val role: String = "user")
    private data class Part(val text: String)
    private data class GenerationConfig(
        val temperature: Float = 0.2f,
        val maxOutputTokens: Int = 512
    )

    private data class GeminiResponse(
        val candidates: List<Candidate>?
    )

    private data class Candidate(val content: Content?)

    companion object {
        private const val BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
    }
}
