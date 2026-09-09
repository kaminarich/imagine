package com.kaminari.imagine

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * JNI bridge to Real-ESRGAN ncnn Vulkan engine.
 * Runs on-device GPU for fast, offline enhancement.
 */
object Engine {

    private const val TAG = "ImagineEngine"

    private val loaded: Boolean = try {
        System.loadLibrary("engine_jni")
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "Failed to load engine_jni: ${e.message}")
        false
    } catch (e: Throwable) {
        Log.e(TAG, "Failed to load engine_jni", e)
        false
    }

    /** External native methods — calling them when !loaded will throw. */
    external fun initGpu(): Boolean
    external fun gpuCount(): Int
    external fun loadModel(paramPath: String, binPath: String, scale: Int, tilesize: Int, prepadding: Int): Boolean
    external fun processImage(input: ByteArray, width: Int, height: Int, progressSink: Any?): ByteArray?
    external fun destroy()

    /** True if the native library loaded successfully. */
    val nativeAvailable: Boolean get() = loaded

    /** Model definitions matching assets/models/ file names. */
    data class ModelInfo(
        val assetParam: String,
        val assetBin: String,
        val scale: Int,
        val prepadding: Int,
        val label: String,
        /** true if file lives in repo assets; false = downloaded at build time by CI */
        val bundled: Boolean = true
    )

    val MODELS = listOf(
        ModelInfo("models/realesr-animevideov3-x2.param", "models/realesr-animevideov3-x2.bin", 2, 10, "AnimeVideo v3 ×2 (fast)"),
        ModelInfo("models/realesr-animevideov3-x3.param", "models/realesr-animevideov3-x3.bin", 3, 10, "AnimeVideo v3 ×3 (fast)"),
        ModelInfo("models/realesr-animevideov3-x4.param", "models/realesr-animevideov3-x4.bin", 4, 10, "AnimeVideo v3 ×4 (fast)"),
        ModelInfo("models/realesrgan-x4plus-anime.param", "models/realesrgan-x4plus-anime.bin", 4, 10, "Real-ESRGAN ×4 Anime (17 MB)", bundled = false),
        ModelInfo("models/realesrgan-x4plus.param", "models/realesrgan-x4plus.bin", 4, 10, "Real-ESRGAN ×4 Photo (64 MB)", bundled = false)
    )

    private var initialized = false

    /** Initialize Vulkan and extract bundled models from assets to filesDir. */
    fun init(context: Context): Boolean {
        if (initialized) return true
        if (!nativeAvailable) return false
        return try {
            val gpuOk = initGpu()
            if (gpuOk) {
                extractBundledModels(context)
            }
            initialized = gpuOk
            gpuOk
        } catch (e: Throwable) {
            Log.e(TAG, "Engine init failed", e)
            false
        }
    }

    private fun extractBundledModels(context: Context) {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()
        for (m in MODELS.filter { it.bundled }) {
            copyAssetIfNeeded(context, m.assetParam, File(modelsDir, File(m.assetParam).name))
            copyAssetIfNeeded(context, m.assetBin, File(modelsDir, File(m.assetBin).name))
        }
    }

    /** Extract a CI-bundled (non-repo) model that gradle placed in assets. */
    fun ensureModelExtracted(context: Context, model: ModelInfo): Boolean {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()
        val p = copyAssetIfNeeded(context, model.assetParam, File(modelsDir, File(model.assetParam).name))
        val b = copyAssetIfNeeded(context, model.assetBin, File(modelsDir, File(model.assetBin).name))
        return p && b
    }

    private fun copyAssetIfNeeded(context: Context, assetPath: String, dest: File): Boolean {
        if (dest.exists() && dest.length() > 0L) return true
        return try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "Extracted $assetPath -> ${dest.absolutePath} (${dest.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Missing asset $assetPath: ${e.message}")
            false
        }
    }

    fun load(context: Context, model: ModelInfo): Boolean {
        if (!nativeAvailable) return false
        if (!ensureModelExtracted(context, model)) return false
        val dir = File(context.filesDir, "models")
        return try {
            loadModel(
                File(dir, File(model.assetParam).name).absolutePath,
                File(dir, File(model.assetBin).name).absolutePath,
                model.scale, 0, model.prepadding
            )
        } catch (e: Throwable) {
            Log.e(TAG, "loadModel failed", e)
            false
        }
    }

    /** Enhance a bitmap. Returns upscaled bitmap or null on failure. */
    fun process(bitmap: Bitmap, onProgress: (Float) -> Unit = {}): Bitmap? {
        if (!nativeAvailable) return null
        val w = bitmap.width
        val h = bitmap.height

        // ARGB_8888 bitmaps copy out as RGBA bytes via copyPixelsToBuffer
        val rgba = ByteArray(w * h * 4)
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(rgba))

        val sink = object {
            @Suppress("unused")
            fun onProgress(fraction: Float) { onProgress(fraction) }
        }

        val outBytes = try {
            processImage(rgba, w, h, sink) ?: return null
        } catch (e: Throwable) {
            Log.e(TAG, "processImage failed", e)
            return null
        }

        // output size = (w*scale) * (h*scale) * 4  =>  scale = sqrt(size / (w*h*4))
        val rawScale = outBytes.size.toDouble() / (w * h * 4)
        val scale = kotlin.math.round(kotlin.math.sqrt(rawScale)).toInt()
        if (scale < 2 || w * scale * h * scale * 4L != outBytes.size.toLong()) {
            Log.e(TAG, "Unexpected output size: ${outBytes.size} for ${w}x${h} (derived scale=$scale)")
            return null
        }

        val outBitmap = Bitmap.createBitmap(w * scale, h * scale, Bitmap.Config.ARGB_8888)
        outBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(outBytes))
        return outBitmap
    }

    fun cleanup() {
        if (nativeAvailable) runCatching { destroy() }
    }
}
