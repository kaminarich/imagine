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

    init {
        System.loadLibrary("engine_jni")
    }

    external fun initGpu(): Boolean
    external fun gpuCount(): Int
    external fun loadModel(paramPath: String, binPath: String, scale: Int, tilesize: Int, prepadding: Int): Boolean
    external fun processImage(input: ByteArray, width: Int, height: Int): ByteArray?
    external fun destroy()

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
        val gpuOk = initGpu()
        if (gpuOk) {
            extractBundledModels(context)
        }
        initialized = gpuOk
        return gpuOk
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
        if (!ensureModelExtracted(context, model)) return false
        val dir = File(context.filesDir, "models")
        return loadModel(
            File(dir, File(model.assetParam).name).absolutePath,
            File(dir, File(model.assetBin).name).absolutePath,
            model.scale, 0, model.prepadding
        )
    }

    /** Enhance a bitmap. Returns upscaled bitmap or null on failure. */
    fun process(bitmap: Bitmap): Bitmap? {
        val w = bitmap.width
        val h = bitmap.height

        // ARGB_8888 bitmaps copy out as RGBA bytes via copyPixelsToBuffer
        val rgba = ByteArray(w * h * 4)
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(rgba))

        val outBytes = processImage(rgba, w, h) ?: return null

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
        runCatching { destroy() }
    }
}
