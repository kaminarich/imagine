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
 *
 * Flow (community models):
 *   1. POST /v1/files            -> upload PNG, get a public URL
 *   2. GET  /v1/models/{m}       -> resolve latest version ID + input schema
 *   3. POST /v1/predictions      -> { version: "owner/name:{id}", input }
 *   4. GET  /v1/predictions/{id} -> poll until succeeded/failed
 */
object CloudEngine {

    private const val TAG = "CloudEngine"
    private const val BASE_URL = "https://api.replicate.com/v1"
    private var apiKey: String = ""
    private var lastCreateError: String? = null

    fun setKey(key: String) { apiKey = key.trim() }

    data class CloudModel(
        val id: String,           // owner/name
        val name: String,
        val description: String,
        val imageField: String,   // field name that takes the image URL
        val scaleField: String?   // field name that takes the integer scale, or null
    )

    // Schemas verified against Replicate model pages
    val CLOUD_MODELS = listOf(
        CloudModel(
            id = "nightmareai/real-esrgan",
            name = "Real-ESRGAN",
            description = "General upscaler + GFPGAN face enhance",
            imageField = "image",
            scaleField = "scale"
        ),
        CloudModel(
            id = "sczhou/codeformer",
            name = "CodeFormer",
            description = "Face restoration with fidelity control",
            imageField = "image",
            scaleField = "upscale"
        ),
        CloudModel(
            id = "tencentarc/gfpgan",
            name = "GFPGAN",
            description = "Face + detail enhancement",
            imageField = "img",
            scaleField = "scale"
        )
    )

    suspend fun enhance(
        bitmap: Bitmap,
        modelId: String,
        scale: Int = 4,
        onProgress: (String) -> Unit
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(Exception("Replicate API token is empty — enter it in the cloud panel"))
            }

            val model = CLOUD_MODELS.find { it.id == modelId }
                ?: return@withContext Result.failure(Exception("Unknown model: $modelId"))

            // 1. upload file
            onProgress("Uploading image…")
            val pngBytes = ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()

            val imageUrl = uploadFile(pngBytes)
                ?: return@withContext Result.failure(Exception("Upload failed — check network and token permissions"))

            // 2. resolve latest version id for the community model
            onProgress("Preparing prediction…")
            val version = resolveLatestVersion(model.id)
                ?: return@withContext Result.failure(Exception("Cannot resolve model version for ${model.id} (bad token or model removed)"))

            // 3. create prediction
            onProgress("Starting ${model.name}…")
            val input = JSONObject().apply {
                put(model.imageField, imageUrl)
                model.scaleField?.let { put(it, scale) }
                if (model.id == "nightmareai/real-esrgan") put("face_enhance", true)
                if (model.id == "sczhou/codeformer") {
                    put("codeformer_fidelity", 0.7)
                    put("background_enhance", true)
                    put("face_upsample", true)
                }
            }

            val prediction = createPrediction("${model.id}:$version", input)
                ?: return@withContext Result.failure(Exception(lastCreateError ?: "Failed to create prediction"))

            // sync-mode responses may already be done; async returns id only
            val predictionId = prediction.optString("id")
            if (predictionId.isBlank()) {
                return@withContext Result.failure(Exception("Failed to create prediction (no id)"))
            }

            if (prediction.optString("status") == "succeeded") {
                val url = firstOutputUrl(prediction)
                if (url.isNotBlank()) {
                    return@withContext downloadResult(url, onProgress)
                }
            }

            // 4. poll until terminal
            return@withContext pollForResult(predictionId, onProgress)
        } catch (e: Exception) {
            Log.e(TAG, "Cloud enhance failed", e)
            Result.failure(e)
        }
    }

    private suspend fun pollForResult(
        predictionId: String,
        onProgress: (String) -> Unit
    ): Result<Bitmap> {
        val maxAttempts = 150 // ~5 min at 2s
        for (attempt in 1..maxAttempts) {
            delay(2000)
            val status = getPredictionStatus(predictionId)
                ?: return Result.failure(Exception("Lost connection while polling"))
            when (val state = status.optString("status", "")) {
                "succeeded" -> {
                    val url = firstOutputUrl(status)
                    if (url.isBlank()) return Result.failure(Exception("No output URL in result"))
                    return downloadResult(url, onProgress)
                }
                "failed", "canceled" -> {
                    val error = status.optString("error", state)
                    return Result.failure(Exception("Prediction $state: $error"))
                }
                "starting" -> onProgress("Queued on cloud GPU…")
                "processing" -> onProgress("Processing… ($attempt/$maxAttempts)")
                else -> onProgress("Waiting… ($state)")
            }
        }
        return Result.failure(Exception("Timed out after ~5 minutes"))
    }

    private suspend fun downloadResult(url: String, onProgress: (String) -> Unit): Result<Bitmap> {
        onProgress("Downloading result…")
        val bmp = downloadImage(url) ?: return Result.failure(Exception("Failed to download result"))
        return Result.success(bmp)
    }

    // ── HTTP helpers ────────────────────────────────────────────────────────

    private fun authHeaders(conn: HttpURLConnection) {
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
    }

    /** POST /v1/files (multipart) — returns the public file URL. */
    private fun uploadFile(pngBytes: ByteArray): String? {
        return try {
            val boundary = "----imagine${System.currentTimeMillis()}"
            val url = URL("$BASE_URL/files")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            authHeaders(conn)
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 60000

            conn.outputStream.use { out ->
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"content\"; filename=\"image.png\"\r\n".toByteArray())
                out.write("Content-Type: image/png\r\n\r\n".toByteArray())
                out.write(pngBytes)
                out.write("\r\n--$boundary--\r\n".toByteArray())
                out.flush()
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.readErrorBody()
                Log.e(TAG, "upload failed $code: $err")
                return null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            // response: {"id": "...", "urls": {"get": "https://replicate.delivery/..."}}
            json.optJSONObject("urls")?.optString("get")
                ?: json.optString("url")
        } catch (e: Exception) {
            Log.e(TAG, "uploadFile error", e)
            null
        }
    }

    /** GET /v1/models/{owner}/{name} — latest_version.id. */
    private fun resolveLatestVersion(modelId: String): String? {
        return try {
            val url = URL("$BASE_URL/models/$modelId")
            val conn = url.openConnection() as HttpURLConnection
            authHeaders(conn)
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            if (conn.responseCode !in 200..299) {
                Log.e(TAG, "resolveLatestVersion failed ${conn.responseCode}")
                return null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            json.optJSONObject("latest_version")?.optString("id")
        } catch (e: Exception) {
            Log.e(TAG, "resolveLatestVersion error", e)
            null
        }
    }

    /** POST /v1/predictions with Prefer: wait (sync up to 60s). */
    private fun createPrediction(versionRef: String, input: JSONObject): JSONObject? {
        return try {
            val url = URL("$BASE_URL/predictions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            authHeaders(conn)
            conn.setRequestProperty("Content-Type", "application/json")
            // ask Replicate to hold the request until done (up to 60s)
            conn.setRequestProperty("Prefer", "wait")
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 70000 // slightly longer than the wait

            val body = JSONObject().apply {
                put("version", versionRef)
                put("input", input)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                val err = conn.readErrorBody()
                Log.e(TAG, "createPrediction failed $code: $err")
                lastCreateError = "HTTP $code: ${parseErrorMessage(err)}"
                return null
            }
            lastCreateError = null
            JSONObject(text)
        } catch (e: Exception) {
            Log.e(TAG, "createPrediction error", e)
            lastCreateError = e.message
            null
        }
    }

    /** GET /v1/predictions/{id}. */
    private fun getPredictionStatus(predictionId: String): JSONObject? {
        return try {
            val url = URL("$BASE_URL/predictions/$predictionId")
            val conn = url.openConnection() as HttpURLConnection
            authHeaders(conn)
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            if (conn.responseCode !in 200..299) {
                Log.e(TAG, "getPredictionStatus failed ${conn.responseCode}")
                return null
            }
            JSONObject(conn.inputStream.bufferedReader().readText())
        } catch (e: Exception) {
            Log.e(TAG, "getPredictionStatus error", e)
            null
        }
    }

    /** Download an image from a URL (Replicate outputs need auth too). */
    private fun downloadImage(urlStr: String): Bitmap? {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            authHeaders(conn)
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            if (conn.responseCode !in 200..299) {
                Log.e(TAG, "downloadImage failed ${conn.responseCode}")
                return null
            }
            BitmapFactory.decodeStream(conn.inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "downloadImage error", e)
            null
        }
    }

    /** Output can be a string URL or an array of strings. */
    private fun firstOutputUrl(status: JSONObject): String {
        val out = status.opt("output") ?: return ""
        if (out is String) return out
        return try {
            val arr = org.json.JSONArray(out.toString())
            if (arr.length() > 0) arr.optString(0) else ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun HttpURLConnection.readErrorBody(): String =
        try {
            errorStream?.bufferedReader()?.readText() ?: "(no body)"
        } catch (e: Exception) {
            "(cannot read error body)"
        }

    private fun parseErrorMessage(body: String): String =
        try {
            JSONObject(body).optString("detail").ifBlank { body.take(200) }
        } catch (e: Exception) {
            body.take(200)
        }
}
