package com.kaminari.imagine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Replicate cloud API for Real-ESRGAN / GFPGAN / CodeFormer.
 * Fallback when device GPU unavailable or for latest models.
 */
object CloudEngine {

    private const val TAG = "CloudEngine"
    private const val BASE_URL = "https://api.replicate.com/v1"
    private var apiKey: String = ""

    fun setKey(key: String) { apiKey = key }

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

            // Convert to base64
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.DEFAULT)
            val dataUrl = "data:image/png;base64,$base64"

            // Create prediction
            onProgress("Uploading to cloud...")
            val predictionId = createPrediction(modelId, dataUrl, scale)
                ?: return@withContext Result.failure(Exception("Failed to create prediction"))

            onProgress("Processing (cloud)...")

            // Poll for result
            var attempts = 0
            while (attempts < 120) {
                kotlinx.coroutines.delay(2000)
                val status = getPredictionStatus(predictionId)
                val statusStr = status.optString("status", "unknown")

                when (statusStr) {
                    "succeeded" -> {
                        val outputUrl = status.optJSONObject("output")?.optString("0")
                            ?: status.optString("output", "")
                        if (outputUrl.isBlank()) {
                            return@withContext Result.failure(Exception("No output URL"))
                        }
                        onProgress("Downloading result...")
                        val result = downloadImage(outputUrl)
                            ?: return@withContext Result.failure(Exception("Download failed"))
                        return@withContext Result.success(result)
                    }
                    "failed" -> {
                        val error = status.optString("error", "Unknown error")
                        return@withContext Result.failure(Exception("Prediction failed: $error"))
                    }
                    "starting", "processing" -> {
                        onProgress("Processing... (${status.optString("progress", "")})")
                    }
                }
                attempts++
            }
            Result.failure(Exception("Timeout"))
        } catch (e: Exception) {
            Log.e(TAG, "Cloud enhance failed", e)
            Result.failure(e)
        }
    }

    private fun createPrediction(modelId: String, imageDataUrl: String, scale: Int): String? {
        return try {
            // Model-scoped endpoint: always runs the latest version without a version hash
            val url = URL("$BASE_URL/models/$modelId/predictions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Token $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = JSONObject().apply {
                put("input", JSONObject().apply {
                    put("image", imageDataUrl)
                    put("scale", scale)
                })
            }

            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                Log.e(TAG, "Create prediction failed: $responseCode $err")
                return null
            }

            val response = conn.inputStream.bufferedReader().readText()
            JSONObject(response).optString("id")
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
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            JSONObject(conn.inputStream.bufferedReader().readText())
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun downloadImage(urlStr: String): Bitmap? {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            BitmapFactory.decodeStream(conn.inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "downloadImage error", e)
            null
        }
    }
}
