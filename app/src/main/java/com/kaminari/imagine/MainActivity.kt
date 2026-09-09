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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sin

// ── Design tokens ──────────────────────────────────────────────────────────

// Single brand palette: lavender primary, warm neutrals, WCAG-AA text
private val Brand = Color(0xFF8B7CC7)          // primary action
private val BrandDark = Color(0xFF6E5FB1)      // pressed/gradient end
private val BrandContainer = Color(0xFFE9E1F5)
private val OnBrand = Color(0xFFFFFFFF)
private val Bg = Color(0xFFFDF6F0)             // app background
private val SurfaceFlat = Color(0xFFFFFFFF)    // control card surface
private val SurfaceInset = Color(0xFFF1EAE3)   // recessed wells (chips, fields)
private val BorderSubtle = Color(0xFFE3D9CF)
private val TextPrimary = Color(0xFF3D362E)    // high contrast body
private val TextSecondary = Color(0xFF6B6259)  // AA-compliant secondary
private val TextOnBrand = Color(0xFFFFFFFF)

// ── Activity ───────────────────────────────────────────────────────────────

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
    private var useCustomResolution by mutableStateOf(false)
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
                    gpuName = "Vulkan"
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
                    primary = Brand,
                    onPrimary = OnBrand,
                    primaryContainer = BrandContainer,
                    onPrimaryContainer = TextPrimary,
                    surface = Bg,
                    onSurface = TextPrimary,
                    background = Bg,
                    onBackground = TextPrimary,
                    surfaceVariant = SurfaceInset,
                    onSurfaceVariant = TextSecondary,
                    outline = BorderSubtle,
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
                    SelectedBitmapHolder.w = bitmap.width
                    SelectedBitmapHolder.h = bitmap.height
                } else {
                    Toast.makeText(context, "Cannot load image", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val pickImage: () -> Unit = {
            imagePicker.launch("image/*")
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
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Compact header: single row ──
            CompactHeader(
                useCloud = useCloud,
                gpuReady = engineReady,
                onEngineChange = { useCloud = it }
            )

            Spacer(Modifier.height(12.dp))

            // ── Preview canvas ──
            if (selectedBitmap == null) {
                EmptyStateSection(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    onPick = pickImage
                )
            } else if (enhancedBitmap != null && showCompare) {
                CompareCanvas(
                    before = selectedBitmap!!,
                    after = enhancedBitmap!!,
                    sliderPosition = sliderPosition,
                    onSliderChange = { sliderPosition = it },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                PreviewCanvas(
                    bitmap = enhancedBitmap ?: selectedBitmap!!,
                    isProcessing = isProcessing,
                    processingStatus = processingStatus,
                    progress = enhanceProgress,
                    isEnhanced = enhancedBitmap != null,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Controls ──
            if (selectedBitmap != null) {
                if (useCloud) {
                    CloudPanel(
                        selected = selectedCloudModel,
                        onSelect = { selectedCloudModel = it },
                        apiKey = cloudApiKey,
                        onApiKeyChange = {
                            cloudApiKey = it
                            getPreferences(MODE_PRIVATE).edit()
                                .putString("replicate_api_key", it.trim()).apply()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    CloudScalePanel(
                        scale = scaleMultiplier,
                        onScaleChange = { scaleMultiplier = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    DevicePanel(
                        selected = selectedModel,
                        onSelect = { selectedModel = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    DeviceScalePanel(
                        selected = selectedModel,
                        useCustom = useCustomResolution,
                        onUseCustomChange = { useCustomResolution = it },
                        resolution = targetResolution,
                        onResolutionChange = { targetResolution = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(14.dp))

                // ── CTA flow ──
                ActionFlow(
                    isProcessing = isProcessing,
                    hasResult = enhancedBitmap != null,
                    onEnhance = {
                        if (useCloud) {
                            if (cloudApiKey.isBlank()) {
                                Toast.makeText(context, "Cloud mode needs a Replicate API token.", Toast.LENGTH_LONG).show()
                                return@ActionFlow
                            }
                        } else if (!engineReady) {
                            Toast.makeText(context, "GPU not available. Try cloud mode.", Toast.LENGTH_LONG).show()
                            return@ActionFlow
                        }
                        scope.launch { processImage(context) }
                    },
                    onRepick = pickImage,
                    onSave = {
                        enhancedBitmap?.let { bmp ->
                            scope.launch { saveImage(bmp, context) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(24.dp))
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
                    processingStatus = "Loading model…"
                    val loaded = Engine.load(context, selectedModel)
                    if (!loaded) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Model unavailable: ${selectedModel.label}", Toast.LENGTH_LONG).show()
                        }
                        return@withContext
                    }
                    processingStatus = "Enhancing…"
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
                                "Enhancement failed — image too large for this model. Try a smaller input.",
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
        if (target.isBlank() || !useCustomResolution) return src
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
        val bytes = stream.readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxDim * 2 || bounds.outHeight / sample > maxDim * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
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

// ── Display-size guard ─────────────────────────────────────────────────────

/**
 * Hardware canvases refuse to record bitmaps larger than ~100MB into a
 * display list. The UI draws a downscaled *display copy* while the
 * full-resolution bitmap is kept for saving.
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

// ── Header ─────────────────────────────────────────────────────────────────

/** One-line header: title, engine toggle, GPU badge. */
@Composable
private fun CompactHeader(
    useCloud: Boolean,
    gpuReady: Boolean,
    onEngineChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Imagine",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            fontFamily = FontFamily.Serif
        )

        Spacer(Modifier.weight(1f))

        // Segmented engine toggle
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceInset)
                .padding(2.dp)
        ) {
            HeaderSegment("Device", !useCloud, enabled = gpuReady) { onEngineChange(false) }
            HeaderSegment("Cloud", useCloud, enabled = true) { onEngineChange(true) }
        }
    }
}

@Composable
private fun HeaderSegment(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Brand else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            // AA contrast: unselected uses dark text on light well, selected white on brand
            color = if (selected) TextOnBrand else TextSecondary
        )
    }
}

// ── Empty state ────────────────────────────────────────────────────────────

@Composable
private fun EmptyStateSection(modifier: Modifier = Modifier, onPick: () -> Unit) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .shadow(4.dp, RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(BrandContainer),
            contentAlignment = Alignment.Center
        ) {
            Text("✦", fontSize = 40.sp, color = BrandDark)
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Enhance any photo with Real-ESRGAN",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "On-device GPU · offline · private",
            fontSize = 13.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Choose Image", onClick = onPick, modifier = Modifier.fillMaxWidth(0.7f))
    }
}

// ── Preview canvas with zoom ───────────────────────────────────────────────

/**
 * Preview image with Fit/1:1 zoom toggle (double-tap or pinch)
 * and the water-fill progress overlay while processing.
 */
@Composable
private fun PreviewCanvas(
    bitmap: Bitmap,
    isProcessing: Boolean,
    processingStatus: String,
    progress: Float,
    isEnhanced: Boolean,
    modifier: Modifier = Modifier
) {
    val displayBitmap = rememberDisplayBitmap(bitmap)
    val aspectRatio = bitmap.width.toFloat() / bitmap.height
    var zoomed by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .aspectRatio(aspectRatio.coerceIn(0.55f, 1.9f))
            .shadow(6.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFFEFEBE6))
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { zoomed = !zoomed }
                )
            }
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    if (gestureZoom != 1f) {
                        zoomed = true
                        scale = (scale * gestureZoom).coerceIn(1f, 6f)
                    }
                    if (zoomed) offset += pan
                }
            }
    ) {
        Image(
            bitmap = displayBitmap.asImageBitmap(),
            contentDescription = if (isEnhanced) "Enhanced" else "Selected",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = if (zoomed) scale else 1f
                    scaleY = if (zoomed) scale else 1f
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = if (zoomed) ContentScale.Crop else ContentScale.Fit
        )

        if (isProcessing) {
            WaterFillProgress(
                progress = progress,
                status = processingStatus,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // result badge (small, corner)
            Box(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                Text(
                    if (isEnhanced) "ENHANCED" else "ORIGINAL",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = Color.White,
                    modifier = Modifier
                        .background(Color(0xB3000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            // zoom hint
            if (!zoomed) {
                Text(
                    "Double-tap for 100%",
                    fontSize = 11.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(Color(0x88000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

// ── Water-fill progress ────────────────────────────────────────────────────

/**
 * Liquid progress overlay: the area below the water line is tinted,
 * the surface is two sine waves drifting horizontally, and the fill
 * height tracks [progress] (0..1).
 */
@Composable
private fun WaterFillProgress(
    progress: Float,
    status: String,
    modifier: Modifier = Modifier
) {
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

    val fillFraction = progress.coerceIn(0f, 1f)

    Box(modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val waterY = h * (1f - fillFraction)

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

            drawPath(
                path = wavePath(amplitude = 10f, wavelength = 260f, phaseShift = 1.2f),
                color = Brand.copy(alpha = 0.25f)
            )
            drawPath(
                path = wavePath(amplitude = 16f, wavelength = 180f, phaseShift = 0f),
                color = Brand.copy(alpha = 0.45f)
            )
            drawLine(
                color = BrandDark.copy(alpha = 0.8f),
                start = Offset(0f, waterY),
                end = Offset(w, waterY),
                strokeWidth = 2f
            )
        }

        Text(
            text = if (status.isNotBlank()) status else "Enhancing…",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
                .background(Color(0xCCFDF6F0), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )

        Text(
            text = "${(fillFraction * 100).toInt()}%",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            modifier = Modifier
                .align(Alignment.Center)
                .background(Color(0xCCFDF6F0), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}

// ── Comparison canvas ──────────────────────────────────────────────────────

/**
 * Minimal split-slider comparison: after image fills the frame, the before
 * image is clipped left of the divider. A slim line + circular handle marks
 * the split; BEFORE/AFTER badges appear only while actively dragging.
 * Double-tap toggles 1:1 zoom for pixel peeping.
 */
@Composable
private fun CompareCanvas(
    before: Bitmap,
    after: Bitmap,
    sliderPosition: Float,
    onSliderChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val displayBefore = rememberDisplayBitmap(before)
    val displayAfter = rememberDisplayBitmap(after)
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var isDragging by remember { mutableStateOf(false) }
    val aspectRatio = before.width.toFloat() / before.height
    var zoomed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .aspectRatio(aspectRatio.coerceIn(0.55f, 1.9f))
            .shadow(6.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFFEFEBE6))
            .onGloballyPositioned { containerSize = it.size }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { zoomed = !zoomed }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isDragging = true
                    if (containerSize.width > 0) {
                        onSliderChange((down.position.x / containerSize.width).coerceIn(0.02f, 0.98f))
                    }
                    try {
                        drag(down.id) { change ->
                            if (containerSize.width > 0) {
                                onSliderChange((change.position.x / containerSize.width).coerceIn(0.02f, 0.98f))
                            }
                            change.consume()
                        }
                    } finally {
                        isDragging = false
                    }
                }
            }
    ) {
        val contentScale = if (zoomed) ContentScale.Crop else ContentScale.Fit

        // after (full frame)
        Image(
            bitmap = displayAfter.asImageBitmap(),
            contentDescription = "Enhanced",
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale
        )

        // before (clipped left of the split)
        if (containerSize.width > 0) {
            val clipWidth = containerSize.width * sliderPosition
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
                    contentScale = contentScale
                )
            }

            // divider + handle (minimal)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val x = size.width * sliderPosition
                // slim line
                drawLine(
                    color = Color.White,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 4f
                )
                drawLine(
                    color = BrandDark.copy(alpha = 0.6f),
                    start = Offset(x + 4f, 0f),
                    end = Offset(x + 4f, size.height),
                    strokeWidth = 1.5f
                )
                // circular drag handle
                val cy = size.height / 2
                drawCircle(Color.White, radius = 22f, center = Offset(x, cy))
                drawCircle(Brand, radius = 18f, center = Offset(x, cy))
                // chevrons
                drawLine(
                    Color.White, Offset(x - 9f, cy - 5f), Offset(x - 4f, cy), 3f
                )
                drawLine(
                    Color.White, Offset(x - 9f, cy + 5f), Offset(x - 4f, cy), 3f
                )
                drawLine(
                    Color.White, Offset(x + 4f, cy - 5f), Offset(x + 9f, cy), 3f
                )
                drawLine(
                    Color.White, Offset(x + 4f, cy + 5f), Offset(x + 9f, cy), 3f
                )
            }

            // transient badges only while dragging
            if (isDragging) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Text(
                        "BEFORE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        color = Color.White,
                        modifier = Modifier
                            .background(Color(0xB3000000), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                    Text(
                        "AFTER",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        color = Color.White,
                        modifier = Modifier
                            .background(Color(0xB3302855), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

// ── Flat control cards ─────────────────────────────────────────────────────

/** Flat white card with a single hairline border — no nested boxes. */
@Composable
private fun FlatCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .shadow(2.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceFlat)
            .padding(14.dp),
        content = content
    )
}

/** Card section label — consistent typography. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = TextSecondary
    )
}

// ── On-device model panel (dropdown) ───────────────────────────────────────

@Composable
private fun DevicePanel(
    selected: Engine.ModelInfo,
    onSelect: (Engine.ModelInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    FlatCard(modifier) {
        SectionLabel("Model")
        Spacer(Modifier.height(8.dp))

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = TextPrimary
                )
            ) {
                Text(
                    selected.label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text("▾", color = BrandDark)
            }

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .exposedDropdownSize()
                    .background(SurfaceFlat)
            ) {
                Engine.MODELS.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    model.label,
                                    fontSize = 14.sp,
                                    fontWeight = if (model == selected) FontWeight.Bold else FontWeight.Normal,
                                    color = TextPrimary
                                )
                                Text(
                                    "${model.scale}× upscale" + if (!model.bundled) " · large" else " · fast",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                        },
                        leadingIcon = if (model == selected) {
                            { Text("✓", color = BrandDark, fontWeight = FontWeight.Bold) }
                        } else null,
                        onClick = {
                            onSelect(model)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

// ── On-device scale panel (preset chips) ───────────────────────────────────

@Composable
private fun DeviceScalePanel(
    selected: Engine.ModelInfo,
    useCustom: Boolean,
    onUseCustomChange: (Boolean) -> Unit,
    resolution: String,
    onResolutionChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    FlatCard(modifier) {
        SectionLabel("Output size")
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetChip(
                label = "${selected.scale}×",
                selected = !useCustom,
                onClick = { onUseCustomChange(false) }
            )
            PresetChip(
                label = "Original",
                selected = !useCustom && resolution.equals("original", ignoreCase = true),
                onClick = {
                    onUseCustomChange(false)
                    onResolutionChange("original")
                }
            )
            PresetChip(
                label = "Custom",
                selected = useCustom,
                onClick = { onUseCustomChange(true) }
            )
        }

        if (useCustom) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = if (resolution.equals("original", true)) "" else resolution,
                onValueChange = { input ->
                    onResolutionChange(input.filter { it.isDigit() || it == 'x' || it == 'X' || it == '×' })
                },
                placeholder = {
                    Text(
                        "e.g. 3840x2160 (locked to aspect)",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                },
                supportingText = {
                    val w = selectedBitmapW(selected)
                    val h = selectedBitmapH(selected)
                    if (w != null && h != null) {
                        Text(
                            "Result keeps ${w}×${h} aspect ratio",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 14.sp,
                    color = TextPrimary
                ),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Brand,
                    unfocusedBorderColor = BorderSubtle,
                    cursorColor = Brand,
                    focusedLabelColor = BrandDark,
                    unfocusedLabelColor = TextSecondary
                ),
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

// helpers reading the selected bitmap dims for the aspect hint
private fun selectedBitmapW(@Suppress("UNUSED_PARAMETER") model: Engine.ModelInfo): Int? = SelectedBitmapHolder.w
private fun selectedBitmapH(@Suppress("UNUSED_PARAMETER") model: Engine.ModelInfo): Int? = SelectedBitmapHolder.h

/** Tiny holder so panels can show aspect hints without parameter threading. */
private object SelectedBitmapHolder {
    var w: Int? = null
    var h: Int? = null
}

@Composable
private fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) BrandContainer else SurfaceInset)
            .border(
                1.dp,
                if (selected) Brand else BorderSubtle,
                RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) BrandDark else TextSecondary
        )
    }
}

// ── Cloud panels ───────────────────────────────────────────────────────────

@Composable
private fun CloudPanel(
    selected: CloudEngine.CloudModel,
    onSelect: (CloudEngine.CloudModel) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    FlatCard(modifier) {
        SectionLabel("Cloud model")
        Spacer(Modifier.height(8.dp))

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) {
                Text(
                    selected.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text("▾", color = BrandDark)
            }

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .exposedDropdownSize()
                    .background(SurfaceFlat)
            ) {
                CloudEngine.CLOUD_MODELS.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    model.name,
                                    fontSize = 14.sp,
                                    fontWeight = if (model == selected) FontWeight.Bold else FontWeight.Normal,
                                    color = TextPrimary
                                )
                                Text(
                                    model.description,
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                        },
                        leadingIcon = if (model == selected) {
                            { Text("✓", color = BrandDark, fontWeight = FontWeight.Bold) }
                        } else null,
                        onClick = {
                            onSelect(model)
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        SectionLabel("Replicate API token")
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            placeholder = {
                Text("r8_…", color = TextSecondary, fontSize = 13.sp)
            },
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 14.sp,
                color = TextPrimary
            ),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Brand,
                unfocusedBorderColor = BorderSubtle,
                cursorColor = Brand
            ),
            shape = RoundedCornerShape(12.dp)
        )
        if (apiKey.isBlank()) {
            Text(
                "Get a free token at replicate.com/account/api-tokens",
                fontSize = 11.sp,
                color = TextSecondary,
                modifier = Modifier.padding(start = 2.dp, top = 4.dp)
            )
        }
    }
}

@Composable
private fun CloudScalePanel(
    scale: Int,
    onScaleChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    FlatCard(modifier) {
        SectionLabel("Upscale multiplier")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2, 4).forEach { s ->
                PresetChip(
                    label = "${s}×",
                    selected = scale == s,
                    onClick = { onScaleChange(s) }
                )
            }
        }
    }
}

// ── Action flow (CTA hierarchy) ────────────────────────────────────────────

/**
 * Single clear hierarchy:
 *  - before processing: wide brand "Enhance" + quiet outline "New Image"
 *  - after processing:  prominent brand "Save to Gallery" + outline "Enhance Again"/"New Image"
 */
@Composable
private fun ActionFlow(
    isProcessing: Boolean,
    hasResult: Boolean,
    onEnhance: () -> Unit,
    onRepick: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        if (hasResult) {
            // primary CTA becomes Save
            PrimaryButton(
                label = "Save to Gallery",
                onClick = onSave,
                enabled = !isProcessing,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SecondaryButton(
                    label = "Enhance Again",
                    onClick = onEnhance,
                    enabled = !isProcessing,
                    modifier = Modifier.weight(1f)
                )
                SecondaryButton(
                    label = "New Image",
                    onClick = onRepick,
                    enabled = !isProcessing,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            PrimaryButton(
                label = if (isProcessing) "Working…" else "Enhance",
                onClick = onEnhance,
                enabled = !isProcessing,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            SecondaryButton(
                label = "New Image",
                onClick = onRepick,
                enabled = !isProcessing,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Brand primary button — the single strong color on screen. */
@Composable
private fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.55f)
            .shadow(4.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(Brand, BrandDark)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = TextOnBrand,
            maxLines = 1
        )
    }
}

/** Neutral secondary button — outline style. */
@Composable
private fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.55f)
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceFlat)
            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = TextSecondary,
            maxLines = 1
        )
    }
}
