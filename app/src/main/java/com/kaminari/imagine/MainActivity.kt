@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)

package com.kaminari.imagine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlin.math.sin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var selectedBitmap by mutableStateOf<Bitmap?>(null)
    private var enhancedBitmap by mutableStateOf<Bitmap?>(null)
    private var isProcessing by mutableStateOf(false)
    private var processingStatus by mutableStateOf("")
    private var selectedModel by mutableStateOf(Engine.MODELS.first())
    private var selectedCloudModel by mutableStateOf(CloudEngine.CLOUD_MODELS.first())
    private var useCloud by mutableStateOf(false)
    private var engineReady by mutableStateOf(false)
    private var gpuName by mutableStateOf("")
    private var showCompare by mutableStateOf(false)
    private var sliderPosition by mutableFloatStateOf(0.5f)
    private var scaleMultiplier by mutableIntStateOf(4)
    private var targetResolution by mutableStateOf("")
    private var cloudApiKey by mutableStateOf("")
    private var enhanceProgress by mutableFloatStateOf(0f)
    private var crashReporter: CrashReporter? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        // Install crash logger as early as possible so even init failures are captured
        crashReporter = CrashReporter.install(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // read persisted API token (context is ready here, NOT in property initializers)
        cloudApiKey = getPreferences(MODE_PRIVATE).getString("replicate_api_key", "") ?: ""

        // Init engine (native lib failure must not crash the app)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                engineReady = Engine.init(this@MainActivity)
                if (engineReady) {
                    gpuName = "Vulkan GPU (${Engine.gpuCount()} device(s))"
                } else {
                    CrashReporter.log(this@MainActivity, "Engine", "GPU init failed (no Vulkan or native lib missing)")
                }
            } catch (e: Throwable) {
                Log.e("Imagine", "Engine init failed", e)
                CrashReporter.log(this@MainActivity, "Engine", "init exception", e)
                engineReady = false
            }
        }

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFFB5A6D6),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFE9E1F5),
                    surface = Color(0xFFFDF6F0),
                    onSurface = Color(0xFF5C5470),
                    background = Color(0xFFFDF6F0),
                    onBackground = Color(0xFF5C5470),
                    surfaceVariant = Color(0xFFFFFBF7),
                    onSurfaceVariant = Color(0xFF9A91A8),
                    outline = Color(0xFFE8D9CE),
                    secondary = Color(0xFFF7D6E0),
                    tertiary = Color(0xFFD6E4F7),
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

    @Composable
    private fun MainScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val imagePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let {
                val bitmap = try {
                    contentResolver.openInputStream(it)?.use { stream ->
                        // cap the input so the x4 result stays within memory
                        // and canvas limits (2048*4 = 8192px max edge)
                        decodeSampled(stream, 2048)
                    }
                } catch (e: Exception) { null }
                if (bitmap != null) {
                    selectedBitmap = bitmap
                    enhancedBitmap = null
                    showCompare = false
                } else {
                    Toast.makeText(context, "Cannot load image", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val storagePermission = if (android.os.Build.VERSION.SDK_INT >= 33) {
            rememberPermissionState(android.Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            rememberPermissionState(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            HeaderSection(engineReady, gpuName, useCloud, { useCloud = it })

            Spacer(Modifier.height(20.dp))

            // Image area — aspect ratio follows the image itself
            if (selectedBitmap == null) {
                EmptyStateSection {
                    if (storagePermission.status.isGranted) {
                        imagePicker.launch("image/*")
                    } else {
                        storagePermission.launchPermissionRequest()
                    }
                }
            } else if (showCompare && enhancedBitmap != null) {
                ComparisonSection(
                    before = selectedBitmap!!,
                    after = enhancedBitmap!!,
                    sliderPosition = sliderPosition,
                    onSliderChange = { sliderPosition = it }
                )
            } else {
                PreviewSection(
                    bitmap = enhancedBitmap ?: selectedBitmap!!,
                    isProcessing = isProcessing,
                    processingStatus = processingStatus,
                    progress = enhanceProgress,
                    isEnhanced = enhancedBitmap != null
                )
            }

            Spacer(Modifier.height(16.dp))

            // Model selector
            if (selectedBitmap != null) {
                if (useCloud) {
                    CloudModelSelector(
                        models = CloudEngine.CLOUD_MODELS,
                        selected = selectedCloudModel,
                        onSelect = { selectedCloudModel = it },
                        apiKey = cloudApiKey,
                        onApiKeyChange = {
                            cloudApiKey = it
                            getPreferences(MODE_PRIVATE).edit()
                                .putString("replicate_api_key", it.trim()).apply()
                        }
                    )
                } else {
                    ModelSelector(
                        models = Engine.MODELS,
                        selected = selectedModel,
                        onSelect = { selectedModel = it }
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Scale options (multiplier only meaningful for cloud models;
                // on-device models carry their own native scale)
                ScaleOptions(
                    scale = scaleMultiplier,
                    onScaleChange = { scaleMultiplier = it },
                    resolution = targetResolution,
                    onResolutionChange = { targetResolution = it },
                    showMultiplier = useCloud
                )

                Spacer(Modifier.height(12.dp))

                // Action buttons
                ActionButtons(
                    isProcessing = isProcessing,
                    hasResult = enhancedBitmap != null,
                    showCompare = showCompare,
                    onEnhance = {
                        if (useCloud) {
                            if (cloudApiKey.isBlank()) {
                                Toast.makeText(context, "Cloud mode needs a Replicate API token. Enter it above.", Toast.LENGTH_LONG).show()
                                return@ActionButtons
                            }
                        } else if (!engineReady) {
                            Toast.makeText(context, "GPU not available. Try cloud mode.", Toast.LENGTH_LONG).show()
                            return@ActionButtons
                        }
                        scope.launch {
                            processImage(context)
                        }
                    },
                    onCompare = { showCompare = !showCompare },
                    onSave = {
                        enhancedBitmap?.let { bmp ->
                            scope.launch { saveImage(bmp, context) }
                        }
                    },
                    onRepick = {
                        if (storagePermission.status.isGranted) {
                            imagePicker.launch("image/*")
                        } else {
                            storagePermission.launchPermissionRequest()
                        }
                    }
                )
            }
        }
    }

    private suspend fun processImage(context: android.content.Context) {
        val bitmap = selectedBitmap ?: return
        isProcessing = true
        enhancedBitmap = null
        enhanceProgress = 0f

        try {
            withContext(Dispatchers.IO) {
                if (useCloud) {
                    CloudEngine.setKey(cloudApiKey)
                    val result = CloudEngine.enhance(
                        bitmap = bitmap,
                        modelId = selectedCloudModel.id,
                        scale = scaleMultiplier
                    ) { status -> processingStatus = status }
                    result.onSuccess {
                        enhancedBitmap = applyTargetResolution(it)
                        sliderPosition = 0.5f
                        showCompare = true
                    }
                    result.onFailure { e ->
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Cloud error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    processingStatus = "Loading model..."
                    val loaded = Engine.load(context, selectedModel)
                    if (!loaded) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Model unavailable: ${selectedModel.label}", Toast.LENGTH_LONG).show()
                        }
                        return@withContext
                    }
                    processingStatus = "Enhancing on GPU..."
                    val result = Engine.process(bitmap) { fraction ->
                        enhanceProgress = fraction
                        processingStatus = "Enhancing… ${(fraction * 100).toInt()}%"
                    }
                    processingStatus = ""
                    enhancedBitmap = result?.let { applyTargetResolution(it) }
                    if (result != null) {
                        // jump straight to the before/after comparison
                        sliderPosition = 0.5f
                        showCompare = true
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "Enhancement failed — image too large for this model. Try a smaller input or ×2.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
            CrashReporter.log(context, "Enhance", "exception during enhancement", e)
        } finally {
            isProcessing = false
            processingStatus = ""
            enhanceProgress = 0f
        }
    }

    /** If a target resolution like 1920x1080 is set, scale the result to it. */
    private fun applyTargetResolution(src: Bitmap): Bitmap {
        val target = targetResolution.trim()
        if (target.isBlank()) return src
        val m = Regex("(\\d+)\\s*[xX×]\\s*(\\d+)").find(target) ?: return src
        val tw = m.groupValues[1].toIntOrNull() ?: return src
        val th = m.groupValues[2].toIntOrNull() ?: return src
        if (tw <= 0 || th <= 0 || (tw == src.width && th == src.height)) return src
        // refuse targets that would exceed ~256MB (OOM + canvas limit)
        if (tw.toLong() * th * 4 > 256L * 1024 * 1024) {
            return src
        }
        return Bitmap.createScaledBitmap(src, tw, th, true)
    }

    /** Decode a stream to a bitmap no larger than [maxDim], sampling first to avoid OOM. */
    private fun decodeSampled(stream: java.io.InputStream, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // count single pass for bounds
        val bytes = stream.readBytes()
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxDim * 2 || bounds.outHeight / sample > maxDim * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        // final exact cap
        val longest = maxOf(bmp.width, bmp.height)
        if (longest > maxDim) {
            val ratio = maxDim.toFloat() / longest
            bmp = Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * ratio).toInt().coerceAtLeast(1),
                (bmp.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        }
        return bmp
    }

    /** Save to MediaStore. Runs on IO; JPEG keeps 4K-size results from ballooning memory. */
    private suspend fun saveImage(bitmap: Bitmap, context: android.content.Context) {
        withContext(Dispatchers.IO) {
            try {
                // very large bitmaps: drop to JPEG-95 to bound encode time/memory.
                // PNG of a 4x upscale can exceed 100 MB and trigger OOM/ANR.
                val format = if (bitmap.byteCount > 64 * 1024 * 1024) {
                    Bitmap.CompressFormat.JPEG
                } else {
                    Bitmap.CompressFormat.PNG
                }
                val quality = if (format == Bitmap.CompressFormat.JPEG) 95 else 100

                val name = "imagine_${System.currentTimeMillis()}" +
                    if (format == Bitmap.CompressFormat.JPEG) ".jpg" else ".png"

                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE,
                        if (format == Bitmap.CompressFormat.JPEG) "image/jpeg" else "image/png")
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Imagine")
                    }
                }
                val uri = context.contentResolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: throw IllegalStateException("Cannot create MediaStore entry")

                context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                    out.buffered(1 shl 16)
                    if (!bitmap.compress(format, quality, out)) {
                        throw IllegalStateException("Compression failed")
                    }
                    out.flush()
                } ?: throw IllegalStateException("Cannot open output stream")

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Saved to Pictures/Imagine", Toast.LENGTH_LONG).show()
                }
            } catch (e: Throwable) {
                Log.e("Imagine", "save failed", e)
                CrashReporter.log(context, "Save", "save failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

// ── UI Components ──

/**
 * Hardware canvases refuse to record bitmaps larger than ~100MB into a
 * display list ("Canvas: trying to draw too large bitmap"). 4x upscales of
 * big photos blow past that, so the UI draws a downscaled *display copy*
 * while the full-resolution bitmap is kept for saving.
 */
private const val MAX_DISPLAY_EDGE = 4096

@Composable
private fun rememberDisplayBitmap(bitmap: Bitmap): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    return remember(bitmap) {
        if (longest <= MAX_DISPLAY_EDGE) {
            bitmap
        } else {
            val ratio = MAX_DISPLAY_EDGE.toFloat() / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        }
    }
}

@Composable
private fun HeaderSection(gpuReady: Boolean, gpuName: String, useCloud: Boolean, onToggle: (Boolean) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Imagine",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF5C5470),
            fontFamily = FontFamily.Serif
        )

        Text(
            "AI Image Enhancer",
            fontSize = 14.sp,
            color = Color(0xFF9A91A8),
            fontFamily = FontFamily.Serif
        )

        Spacer(Modifier.height(8.dp))

        // Engine toggle
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFFE9E1F5), Color(0xFFF7D6E0))
                    ),
                    RoundedCornerShape(20.dp)
                )
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            EngineToggleChip("On-Device GPU", !useCloud, gpuReady) {
                onToggle(false)
            }
            EngineToggleChip("Cloud AI", useCloud, true) {
                onToggle(true)
            }
        }

        if (gpuReady && !useCloud) {
            Text(
                "GPU: Vulkan",
                fontSize = 11.sp,
                color = Color(0xFF9A91A8),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun EngineToggleChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .then(
                if (selected) {
                    Modifier
                        .shadow(2.dp, RoundedCornerShape(18.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFFB5A6D6), Color(0xFFC4B5E6))
                            ),
                            RoundedCornerShape(18.dp)
                        )
                } else Modifier
            )
            .clickable(enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color.White else Color(0xFF9A91A8),
            fontFamily = FontFamily.Default
        )
    }
}

@Composable
private fun EmptyStateSection(onPick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .shadow(12.dp, RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFFFFFBF7), Color(0xFFFDF6F0))
                ),
                RoundedCornerShape(28.dp)
            )
            .border(
                1.dp,
                Brush.horizontalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFE8D9CE))),
                RoundedCornerShape(28.dp)
            )
            .clickable { onPick() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                "✦",
                fontSize = 48.sp,
                color = Color(0xFFB5A6D6)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Choose an image to enhance",
                fontSize = 18.sp,
                color = Color(0xFF5C5470),
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Real-ESRGAN • GFPGAN • CodeFormer",
                fontSize = 12.sp,
                color = Color(0xFF9A91A8),
                fontFamily = FontFamily.Default
            )
        }
    }
}

@Composable
private fun PreviewSection(
    bitmap: Bitmap,
    isProcessing: Boolean,
    processingStatus: String,
    progress: Float,
    isEnhanced: Boolean
) {
    val aspectRatio = bitmap.width.toFloat() / bitmap.height
    val displayBitmap = rememberDisplayBitmap(bitmap)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // the frame hugs the image's own aspect ratio (capped so tall
            // images don't eat the whole screen)
            .aspectRatio(aspectRatio.coerceIn(0.55f, 1.9f))
            .shadow(12.dp, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFFEFEBE6))
            .border(
                1.dp,
                Brush.horizontalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFE8D9CE))),
                RoundedCornerShape(28.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = displayBitmap.asImageBitmap(),
            contentDescription = if (isEnhanced) "Enhanced" else "Selected",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        // Water-fill progress: liquid rises from the bottom with animated waves
        if (isProcessing) {
            WaterFillProgress(
                progress = progress,
                status = processingStatus,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (isEnhanced && !isProcessing) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
            ) {
                Label("ENHANCED", Color(0xFFD9F2E6))
            }
        }
    }
}

/**
 * Liquid progress overlay: the area below the water line is tinted,
 * the surface is two sine waves drifting horizontally, and the fill
 * height tracks [progress] (0..1). A status line floats above the water.
 */
@Composable
private fun WaterFillProgress(
    progress: Float,
    status: String,
    modifier: Modifier = Modifier
) {
    // two phases so the waves feel alive
    val transition = rememberInfiniteTransition(label = "water")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val fillFraction = (progress.coerceIn(0f, 1f))

    Box(modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val waterY = h * (1f - fillFraction)

            // wave geometry: front wave big, back wave small and offset
            fun wavePath(amplitude: Float, wavelength: Float, phaseShift: Float): Path {
                val path = Path()
                path.moveTo(0f, h)
                var x = 0f
                while (x <= w) {
                    val y = waterY + amplitude * sin((x / wavelength) * (2f * Math.PI).toFloat() + phase + phaseShift)
                    if (x == 0f) path.lineTo(0f, y) else path.lineTo(x, y)
                    x += 8f
                }
                path.lineTo(w, h)
                path.close()
                return path
            }

            // back wave (lighter, translucent)
            drawPath(
                path = wavePath(amplitude = 10f, wavelength = 260f, phaseShift = 1.2f),
                color = Color(0xFFB5A6D6).copy(alpha = 0.25f)
            )
            // front wave (stronger tint)
            drawPath(
                path = wavePath(amplitude = 16f, wavelength = 180f, phaseShift = 0f),
                color = Color(0xFFB5A6D6).copy(alpha = 0.45f)
            )
            // crisp water line
            drawLine(
                color = Color(0xFF9C89C4).copy(alpha = 0.8f),
                start = Offset(0f, waterY),
                end = Offset(w, waterY),
                strokeWidth = 2f
            )
        }

        // status text floats above the water line
        Text(
            text = if (status.isNotBlank()) status else "Enhancing…",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF5C5470),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
                .background(Color(0xCCFDF6F0), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )

        // percentage badge
        Text(
            text = "${(fillFraction * 100).toInt()}%",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF5C5470),
            modifier = Modifier
                .align(Alignment.Center)
                .background(Color(0xCCFDF6F0), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun ComparisonSection(
    before: Bitmap,
    after: Bitmap,
    sliderPosition: Float,
    onSliderChange: (Float) -> Unit
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val aspectRatio = before.width.toFloat() / before.height
    val displayBefore = rememberDisplayBitmap(before)
    val displayAfter = rememberDisplayBitmap(after)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // frame matches the image aspect ratio (capped for extreme shapes)
            .aspectRatio(aspectRatio.coerceIn(0.55f, 1.9f))
            .shadow(12.dp, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFFEFEBE6))
            .border(
                1.dp,
                Brush.horizontalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFE8D9CE))),
                RoundedCornerShape(28.dp)
            )
            .onGloballyPositioned { containerSize = it.size }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (containerSize.width > 0) {
                        onSliderChange((down.position.x / containerSize.width).coerceIn(0.02f, 0.98f))
                    }
                    drag(down.id) { change ->
                        if (containerSize.width > 0) {
                            onSliderChange((change.position.x / containerSize.width).coerceIn(0.02f, 0.98f))
                        }
                        change.consume()
                    }
                }
            }
    ) {
        // After image (full)
        Image(
            bitmap = displayAfter.asImageBitmap(),
            contentDescription = "Enhanced",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        // Before image (clipped)
        if (containerSize.width > 0) {
            val clipWidth = (containerSize.width * sliderPosition).toFloat()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        clipRect(right = clipWidth) {
                            this@drawWithContent.drawContent()
                        }
                    }
            ) {
                Image(
                    bitmap = displayBefore.asImageBitmap(),
                    contentDescription = "Original",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            // Slider line
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = with(LocalDensity.current) { clipWidth.toDp() })
            ) {
                drawLine(
                    color = Color.White,
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 3f
                )
                // Handle
                drawCircle(
                    color = Color.White,
                    radius = 16f,
                    center = Offset(0f, size.height / 2)
                )
                drawCircle(
                    color = Color(0xFFB5A6D6),
                    radius = 12f,
                    center = Offset(0f, size.height / 2),
                    style = Stroke(width = 2f)
                )
            }
        }

        // Labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Label("BEFORE", Color(0xFFF7D6E0))
            Label("AFTER", Color(0xFFD6E4F7))
        }
    }
}

@Composable
private fun Label(text: String, bgColor: Color) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bgColor.copy(alpha = 0.85f),
        modifier = Modifier.shadow(2.dp, RoundedCornerShape(12.dp))
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF5C5470),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            fontFamily = FontFamily.Default
        )
    }
}

@Composable
private fun ModelSelector(
    models: List<Engine.ModelInfo>,
    selected: Engine.ModelInfo,
    onSelect: (Engine.ModelInfo) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFFFFFBF7), Color(0xFFFDF6F0))
                ),
                RoundedCornerShape(16.dp)
            )
            .border(
                1.dp,
                Brush.horizontalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFE8D9CE))),
                RoundedCornerShape(16.dp)
            )
            .padding(12.dp)
    ) {
        Text(
            "Model",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF5C5470),
            fontFamily = FontFamily.Serif
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(models) { model ->
                ModelChip(
                    label = model.label,
                    scale = "${model.scale}x",
                    selected = model == selected,
                    onClick = { onSelect(model) }
                )
            }
        }
    }
}

@Composable
private fun CloudModelSelector(
    models: List<CloudEngine.CloudModel>,
    selected: CloudEngine.CloudModel,
    onSelect: (CloudEngine.CloudModel) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFFFFFBF7), Color(0xFFFDF6F0))
                ),
                RoundedCornerShape(16.dp)
            )
            .border(
                1.dp,
                Brush.horizontalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFE8D9CE))),
                RoundedCornerShape(16.dp)
            )
            .padding(12.dp)
    ) {
        Text(
            "Cloud Model",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF5C5470),
            fontFamily = FontFamily.Serif
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(models) { model ->
                ModelChip(
                    label = model.name,
                    scale = null,
                    selected = model == selected,
                    onClick = { onSelect(model) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // API key input
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            placeholder = {
                Text("Replicate API token", color = Color(0xFF9A91A8), fontSize = 13.sp)
            },
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 13.sp,
                color = Color(0xFF5C5470)
            ),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFFB5A6D6),
                unfocusedBorderColor = Color(0xFFE8D9CE),
                cursorColor = Color(0xFFB5A6D6)
            ),
            shape = RoundedCornerShape(12.dp)
        )
        if (apiKey.isBlank()) {
            Text(
                "Get a free token at replicate.com/account/api-tokens",
                fontSize = 11.sp,
                color = Color(0xFF9A91A8),
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
    }
}

@Composable
private fun ModelChip(
    label: String,
    scale: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .shadow(if (selected) 3.dp else 0.dp, RoundedCornerShape(20.dp)),
        color = if (selected) Color(0xFFE9E1F5) else Color(0xFFFDF6F0),
        border = if (selected) {
            BorderStroke(1.5.dp, Color(0xFFB5A6D6))
        } else {
            BorderStroke(1.dp, Color(0xFFE8D9CE))
        }
    ) {
        Row(
            modifier = Modifier
                .clickable { onClick() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Color(0xFF5C5470) else Color(0xFF9A91A8),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (scale != null) {
                Text(
                    scale,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFB5A6D6)
                )
            }
        }
    }
}

@Composable
private fun ScaleOptions(
    scale: Int,
    onScaleChange: (Int) -> Unit,
    resolution: String,
    onResolutionChange: (String) -> Unit,
    showMultiplier: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFFFFFBF7), Color(0xFFFDF6F0))
                ),
                RoundedCornerShape(16.dp)
            )
            .border(
                1.dp,
                Brush.horizontalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFE8D9CE))),
                RoundedCornerShape(16.dp)
            )
            .padding(12.dp)
    ) {
        Text(
            "Scale Options",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF5C5470),
            fontFamily = FontFamily.Serif
        )
        Spacer(Modifier.height(8.dp))

        if (showMultiplier) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Multiplier:",
                    fontSize = 12.sp,
                    color = Color(0xFF9A91A8)
                )
                listOf(2, 3, 4).forEach { s ->
                    ScaleChip(
                        value = "${s}x",
                        selected = scale == s,
                        onClick = { onScaleChange(s) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = resolution,
            onValueChange = { input ->
                onResolutionChange(input.filter { it.isDigit() || it == 'x' || it == 'X' || it == '×' })
            },
            placeholder = {
                Text("Target resolution (e.g., 1920x1080)", color = Color(0xFF9A91A8), fontSize = 13.sp)
            },
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 13.sp,
                color = Color(0xFF5C5470)
            ),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFFB5A6D6),
                unfocusedBorderColor = Color(0xFFE8D9CE),
                cursorColor = Color(0xFFB5A6D6)
            ),
            shape = RoundedCornerShape(12.dp)
        )
    }
}

@Composable
private fun ScaleChip(value: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Color(0xFFD9F2E6) else Color(0xFFFDF6F0),
        border = if (selected) {
            BorderStroke(1.5.dp, Color(0xFFB5A6D6))
        } else {
            BorderStroke(1.dp, Color(0xFFE8D9CE))
        }
    ) {
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color(0xFF5C5470) else Color(0xFF9A91A8),
            modifier = Modifier
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun ActionButtons(
    isProcessing: Boolean,
    hasResult: Boolean,
    showCompare: Boolean,
    onEnhance: () -> Unit,
    onCompare: () -> Unit,
    onSave: () -> Unit,
    onRepick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Re-pick image
        SkeuButton(
            label = "New Image",
            color = Color(0xFFFDF3D7),
            enabled = !isProcessing,
            onClick = onRepick,
            modifier = Modifier.weight(1f)
        )

        // Enhance button
        SkeuButton(
            label = if (isProcessing) "Working…" else "Enhance",
            color = Color(0xFFB5A6D6),
            enabled = !isProcessing,
            onClick = onEnhance,
            modifier = Modifier.weight(1.4f)
        )
    }

    if (hasResult) {
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SkeuButton(
                label = if (showCompare) "Preview" else "Compare",
                color = Color(0xFFD6E4F7),
                enabled = !isProcessing,
                onClick = onCompare,
                modifier = Modifier.weight(1f)
            )
            SkeuButton(
                label = "Save",
                color = Color(0xFFD9F2E6),
                enabled = !isProcessing,
                onClick = onSave,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Skeuomorphic raised button: solid pastel body with a light bevel top edge,
 * dark bevel bottom edge and a pressed state that inverts the bevel.
 */
@Composable
private fun SkeuButton(
    label: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    // darker and lighter variants of the body color
    val bodyDark = darken(color, 0.82f)
    val bodyLight = lighten(color, 0.55f)
    val edgeLight = lighten(color, 0.9f)
    val edgeDark = darken(color, 0.6f)

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.55f)
            .shadow(
                elevation = if (enabled) 5.dp else 1.dp,
                shape = shape,
                ambientColor = Color(0xFF5C5470).copy(alpha = 0.45f),
                spotColor = Color(0xFF5C5470).copy(alpha = 0.45f)
            )
            .clip(shape)
            .background(Brush.verticalGradient(listOf(bodyLight, color, bodyDark)), shape)
            .border(1.dp, Brush.verticalGradient(listOf(edgeLight, edgeDark)), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4A4358),
            fontFamily = FontFamily.Default,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/** Darken a pastel color toward its shadow tone. */
private fun darken(c: Color, factor: Float): Color =
    Color(red = c.red * factor, green = c.green * factor, blue = c.blue * factor, alpha = c.alpha)

/** Lighten a pastel color toward its highlight tone. */
private fun lighten(c: Color, factor: Float): Color = Color(
    red = c.red + (1f - c.red) * factor,
    green = c.green + (1f - c.green) * factor,
    blue = c.blue + (1f - c.blue) * factor,
    alpha = c.alpha
)