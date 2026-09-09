@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)

package com.kaminari.imagine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Init engine
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                engineReady = Engine.init(this@MainActivity)
                if (engineReady) {
                    gpuName = "Vulkan GPU (${Engine.gpuCount()} device(s))"
                }
            } catch (e: Exception) {
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
                        BitmapFactory.decodeStream(stream)
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

            // Image area
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
                    bitmap = selectedBitmap!!,
                    isProcessing = isProcessing,
                    processingStatus = processingStatus
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
                        onApiKeyChange = { cloudApiKey = it }
                    )
                } else {
                    ModelSelector(
                        models = Engine.MODELS,
                        selected = selectedModel,
                        onSelect = { selectedModel = it }
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Scale options
                ScaleOptions(
                    scale = scaleMultiplier,
                    onScaleChange = { scaleMultiplier = it },
                    resolution = targetResolution,
                    onResolutionChange = { targetResolution = it }
                )

                Spacer(Modifier.height(12.dp))

                // Action buttons
                ActionButtons(
                    isProcessing = isProcessing,
                    hasResult = enhancedBitmap != null,
                    showCompare = showCompare,
                    onEnhance = {
                        if (!useCloud && !engineReady) {
                            Toast.makeText(context, "GPU not available. Try cloud mode.", Toast.LENGTH_LONG).show()
                            return@ActionButtons
                        }
                        scope.launch {
                            processImage(context)
                        }
                    },
                    onCompare = { showCompare = !showCompare },
                    onSave = {
                        enhancedBitmap?.let { saveImage(it, context) }
                    },
                    onReset = {
                        selectedBitmap = null
                        enhancedBitmap = null
                        showCompare = false
                    }
                )
            }
        }
    }

    private suspend fun processImage(context: android.content.Context) {
        val bitmap = selectedBitmap ?: return
        isProcessing = true
        enhancedBitmap = null

        try {
            withContext(Dispatchers.IO) {
                if (useCloud) {
                    CloudEngine.setKey(cloudApiKey)
                    val result = CloudEngine.enhance(
                        bitmap = bitmap,
                        modelId = selectedCloudModel.id,
                        scale = scaleMultiplier
                    ) { status -> processingStatus = status }
                    result.onSuccess { enhancedBitmap = applyTargetResolution(it) }
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
                    val result = Engine.process(bitmap)
                    processingStatus = ""
                    enhancedBitmap = result?.let { applyTargetResolution(it) }
                    if (result == null) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Enhancement failed (GPU OOM?)", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } finally {
            isProcessing = false
            processingStatus = ""
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
        return Bitmap.createScaledBitmap(src, tw, th, true)
    }

    private fun saveImage(bitmap: Bitmap, context: android.content.Context) {
        try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "imagine_${System.currentTimeMillis()}.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Build.VERSION.SDK_INT.let {
                    if (it >= 29) "Pictures/Imagine" else null
                })
            }
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: throw IllegalStateException("Cannot create MediaStore entry")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IllegalStateException("Compression failed")
            }
            Toast.makeText(context, "Saved to Pictures/Imagine", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

// ── UI Components ──

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
private fun PreviewSection(bitmap: Bitmap, isProcessing: Boolean, processingStatus: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .shadow(12.dp, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .border(
                1.dp,
                Brush.horizontalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFE8D9CE))),
                RoundedCornerShape(28.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Selected",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        if (isProcessing) {
            // Processing overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xAAFFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = Color(0xFFB5A6D6),
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        processingStatus.ifBlank { "Processing..." },
                        fontSize = 14.sp,
                        color = Color(0xFF5C5470),
                        fontFamily = FontFamily.Default
                    )
                }
            }
        }
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .shadow(12.dp, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
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
            bitmap = after.asImageBitmap(),
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
                    bitmap = before.asImageBitmap(),
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
                Text("Replicate API key", color = Color(0xFF9A91A8), fontSize = 13.sp)
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
    onResolutionChange: (String) -> Unit
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

        OutlinedTextField(
            value = resolution,
            onValueChange = onResolutionChange,
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
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Enhance button
        SkeuButton(
            label = if (isProcessing) "Processing..." else "Enhance",
            color = Color(0xFFB5A6D6),
            enabled = !isProcessing,
            onClick = onEnhance,
            modifier = Modifier.weight(1f)
        )

        if (hasResult) {
            // Compare button
            SkeuButton(
                label = if (showCompare) "Preview" else "Compare",
                color = Color(0xFFD6E4F7),
                enabled = !isProcessing,
                onClick = onCompare,
                modifier = Modifier.weight(1f)
            )

            // Save button
            SkeuButton(
                label = "Save",
                color = Color(0xFFD9F2E6),
                enabled = !isProcessing,
                onClick = onSave,
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (hasResult) {
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onReset,
            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF9A91A8))
        ) {
            Text("Reset", fontSize = 13.sp)
        }
    }
}

@Composable
private fun SkeuButton(
    label: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val alpha = if (enabled) 1f else 0.5f
    Box(
        modifier = modifier
            .alpha(alpha)
            .shadow(4.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.6f),
                        color.copy(alpha = 0.3f)
                    )
                ),
                RoundedCornerShape(16.dp)
            )
            .border(
                1.dp,
                color.copy(alpha = 0.4f),
                RoundedCornerShape(16.dp)
            )
            .clickable(enabled) { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF5C5470),
            fontFamily = FontFamily.Default,
            textAlign = TextAlign.Center
        )
    }
}