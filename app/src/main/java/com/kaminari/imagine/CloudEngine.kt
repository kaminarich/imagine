package com.kaminari.imagine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Replicate cloud API for Real-ESRGAN / GFPGAN / CodeFormer.
 * Fallback when device GPU unavailable or for the latest models.
 */
object CloudEngine {

    private const val TAG = "CloudEngine"
    private const val BASE_URL = "https://api.replicate.com/v1"
    private var apiKey: String = ""

    fun setKey(key: String) { apiKey = key.trim() }

    data class CloudModel(
        val id: String,
        val name: String,
        val description: String
    )

    val CLOUD_MODELS = listOf(
        CloudModel("nightmareai/real-esrgan", "Real-ESRGAN", "General upscaler + face enhance"),
        CloudModel("nightmareai/codeformer", "CodeFormer", "Face restoration"),
        CloudModel("tencentarc/gfpgan", "GFPGAN", "Face + detail enhancement")
    )

    suspend fun enhance(
        bitmap: Bitmap,
        modelId: String,
        scale: Int = 4,
        onProgress: (String) -> Unit
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(Exception("API key required"))
            }

            // Replicate model-scoped schemas differ per model
            val input = buildInput(modelId, bitmap, scale)
            onProgress("Uploading to cloud...")
            val predictionId = createPrediction(modelId, input)
                ?: return@withContext Result.failure(Exception("Failed to create prediction"))

            onProgress("Processing (cloud)...")

            // Poll for result (up to ~5 min)
            val maxAttempts = 150
            for (attempt in 1..maxAttempts) {
                delay(2000)
                val status = getPredictionStatus(predictionId)
                when (val statusStr = status.optString("status", "")) {
                    "succeeded" -> {
                        val outputUrl = firstOutputUrl(status)
                        if (outputUrl.isBlank()) {
                            return@withContext Result.failure(Exception("No output URL"))
                        }
                        onProgress("Downloading result...")
                        val result = downloadImage(outputUrl)
                            ?: return@withContext Result.failure(Exception("Download failed"))
                        return@withContext Result.success(result)
                    }
                    "failed", "canceled" -> {
                        val error = status.optString("error", statusStr)
                        return@withContext Result.failure(Exception("Prediction $statusStr: $error"))
                    }
                    "starting" -> onProgress("Queued on cloud GPU...")
                    "processing" -> onProgress("Processing... ($attempt/$maxAttempts)")
                    else -> onProgress("Waiting for status...")
                }
            }
            Result.failure(Exception("Timed out after ~5 minutes"))
        } catch (e: Exception) {
            Log.e(TAG, "Cloud enhance failed", e)
            Result.failure(e)
        }
    }

    /** Build the per-model input JSON. */
    private fun buildInput(modelId: String, bitmap: Bitmap, scale: Int): JSONObject {
        val input = JSONObject().apply {
            put("image", toDataUrl(bitmap))
            when (modelId) {
                "nightmareai/codeformer" -> {
                    put("codeformer_fidelity", 0.7)
                    put("background_enhance", true)
                    put("face_upsample", true)
                    put("upscale", scale)
                }
                "tencentarc/gfpgan" -> {
                    put("upscale", scale)
                }
                else -> { // nightmareai/real-esrgan
                    put("scale", scale)
                    put("face_enhance", true)
                }
            }
        }
        return input
    }

    private fun toDataUrl(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.DEFAULT)
        return "data:image/png;base64,$base64"
    }

    /** Replicate outputs can be a string URL or an array of strings. */
    private fun firstOutputUrl(status: JSONObject): String {
        val out = status.opt("output") ?: return ""
        if (out is String) return out
        if (out is JSONObject) {
            out.optString("0").takeIf { it.isNotBlank() }?.let { return it }
        }
        if (outputArrayToStrings(out).isNotEmpty()) {
            return outputArrayToStrings(out).first()
        }
        return ""
    }

    private fun outputArrayToStrings(obj: Any): List<String> {
        return try {
            val arr = org.json.JSONArray(obj.toString())
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun createPrediction(modelId: String, input: JSONObject): String? {
        return try {
            val url = URL("$BASE_URL/models/$modelId/predictions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Token $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 30000

            val body = JSONObject().apply { put("input", input) }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                Log.e(TAG, "Create prediction failed: $responseCode $err")
                return null
            }
            // response can be 201 (created) or 200 (done synchronously with Prefer: wait)
            JSONObject(conn.inputStream.bufferedReader().readText()).optString("id")
        } catch (e: Exception) {
            Log.e(TAG, "createPrediction error", e)
            null
        }
    }

    private fun getPredictionStatus(predictionId: String): JSONObject {
        return try {
            val url = URL("$BASE_URL/predictions/$predictionId")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Token $apiKey")
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            JSONObject(conn.inputStream.bufferedReader().readText())
        } catch (e: Exception) {
            Log.e(TAG, "getPredictionStatus error", e)
            JSONObject()
        }
    }

    private fun downloadImage(urlStr: String): Bitmap? {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            BitmapFactory.decodeStream(conn.inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "downloadImage error", e)
            null
        }
    }
}
