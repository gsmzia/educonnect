package com.security.myapplication.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.CamcorderProfile
import android.media.ExifInterface
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** The only cache subtree intentionally exposed through the FileProvider. */
private fun cameraMediaDir(context: Context): File = File(context.cacheDir, "camera_media").apply {
    if (!exists()) mkdirs()
}

/**
 * Native Camera Engine — Photo & Video
 * - Long press shutter → start HD video recording (up to 1 hour)
 * - Tap shutter → take instant high-quality photo
 * - Zoom (1x / 2x / 5x / pinch) — works identically for both photo & video
 * - Front & Back camera both supported
 * - Video uses same 3:4 aspect ratio framing as photo
 */
@Composable
fun CameraScreen(
    onPhotoCaptured: (File) -> Unit,
    onVideoCaptured: (File) -> Unit = {},
    onClose: () -> Unit
) {
    val context = LocalContext.current

    var lensFacing by remember { mutableIntStateOf(CameraCharacteristics.LENS_FACING_BACK) }
    var flashEnabled by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var zoomLevel by remember { mutableFloatStateOf(1f) }

    val controller = remember { Camera2Engine(context) }

    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }

    // Recording timer
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingSeconds = 0
            while (isRecording) {
                delay(1000)
                recordingSeconds++
                if (recordingSeconds >= 3600) {
                    // Auto-stop at 1 hour
                    val videoFile = controller.stopVideoRecording()
                    isRecording = false
                    videoFile?.let { onVideoCaptured(it) }
                    break
                }
            }
        }
    }

    fun formatTimer(s: Int): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    val newZoom = (zoomLevel * zoom).coerceIn(1f, controller.maxZoom)
                    if (Math.abs(newZoom - zoomLevel) >= 0.02f) {
                        zoomLevel = newZoom
                        controller.setZoom(newZoom)
                    }
                }
            }
    ) {
        // ── 3:4 Viewfinder Frame ─────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(8.dp))
        ) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                controller.attachViewfinder(this@apply, lensFacing, w, h)
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                controller.updateTextureViewSize(w, h)
                            }
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                controller.detachViewfinder(); return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Recording red dot indicator
            if (isRecording) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF3B3B))
                    )
                    Text(
                        text = formatTimer(recordingSeconds),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // ── Top Controls: Close + Flash ─────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Close / Stop button — purple-tinted background
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF2A1F50), Color(0xFF1A1535)))
                    )
                    .border(1.dp, Color(0xFF8B6BFF).copy(alpha = 0.5f), CircleShape)
                    .noRippleClickable {
                        if (isRecording) {
                            val videoFile = controller.stopVideoRecording()
                            isRecording = false
                            videoFile?.let { onVideoCaptured(it) }
                        } else {
                            onClose()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color(0xFFD0C4FF),
                    modifier = Modifier.size(20.dp)
                )
            }

            if (!isRecording) {
                // Flash button — styled with project colors
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (flashEnabled)
                                Brush.linearGradient(listOf(Color(0xFF5B3FCC), Color(0xFF8B6BFF)))
                            else
                                Brush.linearGradient(listOf(Color(0xFF2A1F50), Color(0xFF1A1535)))
                        )
                        .border(1.dp, Color(0xFF8B6BFF).copy(alpha = 0.5f), CircleShape)
                        .noRippleClickable {
                            flashEnabled = !flashEnabled
                            controller.setFlash(flashEnabled)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (flashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Flash",
                        tint = if (flashEnabled) Color(0xFFFFD600) else Color(0xFFD0C4FF),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // ── Bottom Bar ──────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Zoom Pills — styled with project purple, moved lower
            if (!isRecording) {
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF1A1535).copy(alpha = 0.85f))
                        .border(1.dp, Color(0xFF8B6BFF).copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    listOf(1.0f, 2.0f, 5.0f).forEach { z ->
                        val isSelected = Math.abs(zoomLevel - z) < 0.3f
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) Brush.linearGradient(
                                        listOf(Color(0xFF9D80FF), Color(0xFF5B3FCC))
                                    ) else Brush.linearGradient(
                                        listOf(Color.Transparent, Color.Transparent)
                                    )
                                )
                                .noRippleClickable {
                                    val targetZoom = z.coerceIn(1f, controller.maxZoom)
                                    zoomLevel = targetZoom
                                    controller.setZoom(targetZoom)
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = if (z == 1.0f) "1x" else if (z == 2.0f) "2x" else "5x",
                                color = if (isSelected) Color.White else Color(0xFFB0A8CC),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            } else {
                // WhatsApp-Style Recording Banner Overlay
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .border(1.dp, Color(0xFFFF3B3B).copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF3B3B))
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = formatTimer(recordingSeconds),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = if (zoomLevel > 1.05f) String.format(Locale.US, "🔍 %.1fx", zoomLevel) else "⬆️ Slide UP to Zoom",
                            color = Color(0xFFFF8A8A),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Release finger to finish & send",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 11.sp
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 36.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Camera Switch
                CamRoundBtn(
                    enabled = !isCapturing && !isRecording,
                    onClick = {
                        lensFacing = if (lensFacing == CameraCharacteristics.LENS_FACING_BACK)
                            CameraCharacteristics.LENS_FACING_FRONT
                        else CameraCharacteristics.LENS_FACING_BACK
                        zoomLevel = 1f
                        controller.switchLens(lensFacing)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "Switch Camera",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // ── Shutter / Record Button — 200dp invisible touch zone ──────────
                // Finger stays inside this area while recording. Only the 160dp circle
                // blocks scroll (checked via distance-from-center). Outside circle = scroll OK.
                val shutterScope = rememberCoroutineScope()
                // boxSizePx: half-width of 200dp box in pixels (= center offset)
                val boxSizePx = with(androidx.compose.ui.platform.LocalDensity.current) { 100.dp.toPx() }
                val circleRadiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { 80.dp.toPx() }
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val downTime = System.currentTimeMillis()
                                    var isVideoStarted = false
                                    // zoomAnchorY is set AFTER recording starts to avoid pump
                                    var zoomAnchorY = Float.NaN

                                    val recordJob = shutterScope.launch {
                                        delay(250)
                                        if (!isCapturing) {
                                            val prefix = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) "front_vid_" else "back_vid_"
                                            val outFile = File(cameraMediaDir(context), "${prefix}${System.currentTimeMillis()}.mp4")
                                            val started = controller.startVideoRecording(
                                                outFile,
                                                lensFacing == CameraCharacteristics.LENS_FACING_FRONT
                                            )
                                            if (started != null) {
                                                isRecording = true
                                                isVideoStarted = true
                                                // Anchor zoom to CURRENT finger position — prevents pump
                                                zoomAnchorY = Float.NaN // will be set on next event
                                            }
                                        }
                                    }

                                    // Track finger
                                    while (true) {
                                        val recording = isRecording || isVideoStarted
                                        val pass = if (recording) PointerEventPass.Initial else PointerEventPass.Main
                                        val event = awaitPointerEvent(pass)
                                        val change = event.changes.find { it.id == down.id } ?: event.changes.firstOrNull()
                                        if (change == null || !change.pressed) break

                                        if (recording) {
                                            // Only block scroll while finger is inside the 160dp red circle
                                            val dx = change.position.x - boxSizePx
                                            val dy = change.position.y - boxSizePx
                                            val insideCircle = (dx * dx + dy * dy) <= (circleRadiusPx * circleRadiusPx)
                                            if (insideCircle) event.changes.forEach { it.consume() }

                                            // Zoom: anchor Y is captured fresh after recording starts
                                            if (zoomAnchorY.isNaN()) {
                                                zoomAnchorY = change.position.y
                                            } else {
                                                val dragUp = zoomAnchorY - change.position.y
                                                if (dragUp > 20f) {
                                                    val maxZ = controller.maxZoom
                                                    val fraction = (dragUp / 450f).coerceIn(0f, 1f)
                                                    val targetZoom = (1.0f + (maxZ - 1.0f) * fraction).coerceIn(1.0f, maxZ)
                                                    zoomLevel = targetZoom
                                                    controller.setZoom(targetZoom)
                                                } else if (dragUp <= 5f && zoomLevel > 1.0f) {
                                                    zoomLevel = 1.0f
                                                    controller.setZoom(1.0f)
                                                }
                                            }
                                        }
                                    }

                                    recordJob.cancel()
                                    val heldMs = System.currentTimeMillis() - downTime

                                    if (isRecording || isVideoStarted) {
                                        shutterScope.launch {
                                            val startMs = controller.recordingStartMs.takeIf { it > 0 } ?: downTime
                                            val elapsed = System.currentTimeMillis() - startMs
                                            if (elapsed < 1200) {
                                                delay(1200 - elapsed)
                                            }
                                            val videoFile = controller.stopVideoRecording()
                                            isRecording = false
                                            if (videoFile != null) {
                                                onVideoCaptured(videoFile)
                                            } else {
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    Toast.makeText(context, "Hold longer to record video", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    } else if (heldMs < 250 && !isCapturing) {
                                        isCapturing = true
                                        controller.takePicture(
                                            onSuccess = { file ->
                                                isCapturing = false
                                                onPhotoCaptured(file)
                                            },
                                            onError = { err ->
                                                isCapturing = false
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    Toast.makeText(context, "Error: $err", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // WhatsApp-style outer alignment circle — visible only while recording
                    if (isRecording) {
                        Box(
                            modifier = Modifier
                                .size(160.dp)
                                .clip(CircleShape)
                                .border(2.dp, Color(0xFFFF3B3B).copy(alpha = 0.55f), CircleShape)
                        )
                    }

                    // Actual 80dp visual button
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(
                                if (isRecording) Color(0xFFFF3B3B).copy(alpha = 0.18f)
                                else if (isCapturing) Color(0xFF5B3FCC)
                                else Color.Transparent
                            )
                            .border(
                                4.dp,
                                if (isRecording) Color(0xFFFF3B3B) else Color(0xFF8B6BFF),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isRecording) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFFF3B3B))
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(Color(0xFF9D80FF), Color(0xFF8B6BFF), Color(0xFF5B3FCC))
                                        )
                                    )
                            )
                        }
                    }
                }

                // Balance spacer
                Box(modifier = Modifier.size(50.dp))
            }

            // Long press hint
            if (!isRecording && !isCapturing) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Tap for photo  •  Hold for video",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp
                )
            }
        }

        // Capture flash overlay
        AnimatedVisibility(visible = isCapturing, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.12f))
            )
        }
    }
}

@Composable
private fun CamRoundBtn(
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.52f))
            .noRippleClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun Modifier.noRippleClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.then(
        Modifier.clickable(
            enabled = enabled,
            indication = null,
            interactionSource = interactionSource,
            onClick = onClick
        )
    )
}

/**
 * Camera2Engine — manages preview, photo capture, and video recording.
 */
private class Camera2Engine(private val context: Context) {
    private var textureView: TextureView? = null
    private var lensFacing = CameraCharacteristics.LENS_FACING_BACK

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null
    private var videoOutputFile: File? = null
    private var isVideoRecording = false

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var isFlashOn = false
    private var sensorOrientation = 0
    private var activeArraySize: Rect? = null
    private var currentZoomRatio = 1.0f

    var maxZoom: Float = 5.0f
        private set

    private var currentCameraId: String? = null
    private var previewSize: Size? = null
    private var videoSize: Size? = null
    var recordingStartMs = 0L
    private var bestAfMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
    private var bestFpsRange: Range<Int>? = null
    private var pendingReopenRunnable: Runnable? = null

    fun attachViewfinder(tv: TextureView, facing: Int, viewWidth: Int, viewHeight: Int) {
        textureView = tv
        lensFacing = facing
        startThreadAndOpen(viewWidth, viewHeight)
    }

    fun updateTextureViewSize(width: Int, height: Int) {
        val tv = textureView ?: return
        val ps = previewSize ?: return
        configureTransform(tv, width, height, ps, lensFacing == CameraCharacteristics.LENS_FACING_FRONT)
    }

    fun switchLens(facing: Int) {
        lensFacing = facing
        currentZoomRatio = 1.0f
        pendingReopenRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        closeCameraOnly()
        val tv = textureView ?: return
        val handler = backgroundHandler
        if (handler == null) {
            if (tv.isAvailable) openCamera(tv.width, tv.height)
            return
        }
        // Give the camera HAL a brief moment to fully release the previous
        // device before opening the new one — opening right after close()
        // can silently fail on many devices, which is what made front/back
        // switching feel stuck or show a frozen frame.
        val runnable = Runnable {
            if (textureView != null && tv.isAvailable) openCamera(tv.width, tv.height)
        }
        pendingReopenRunnable = runnable
        handler.postDelayed(runnable, 150)
    }

    fun detachViewfinder() { release() }

    private fun startThreadAndOpen(w: Int, h: Int) {
        if (backgroundThread == null) {
            val thread = HandlerThread("Cam2Thread").apply { start() }
            backgroundThread = thread
            backgroundHandler = Handler(thread.looper)
        }
        openCamera(w, h)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(viewWidth: Int, viewHeight: Int) {
        val tv = textureView ?: return
        if (!tv.isAvailable) return
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == lensFacing
            } ?: manager.cameraIdList.firstOrNull() ?: return

            val chars = manager.getCameraCharacteristics(cameraId)
            currentCameraId = cameraId
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            activeArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.coerceAtLeast(1f) ?: 5f

            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            bestAfMode = when {
                afModes.contains(CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                afModes.contains(CameraCharacteristics.CONTROL_AF_MODE_AUTO) -> CaptureRequest.CONTROL_AF_MODE_AUTO
                else -> CaptureRequest.CONTROL_AF_MODE_OFF
            }

            val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            bestFpsRange = fpsRanges?.maxByOrNull { it.upper }

            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

            // Photo: max JPEG resolution
            val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
            val largestJpeg = jpegSizes?.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
            imageReader = ImageReader.newInstance(largestJpeg.width, largestJpeg.height, ImageFormat.JPEG, 2)

            // Preview: 4:3 matching
            val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)
            val chosen = chooseOptimalPreviewSize(previewSizes, viewWidth, viewHeight)
            previewSize = chosen
            if (viewWidth > 0 && viewHeight > 0) configureTransform(tv, viewWidth, viewHeight, chosen, lensFacing == CameraCharacteristics.LENS_FACING_FRONT)

            // Video: pick a size close to 4:3 so recorded video keeps the SAME
            // 3:4 framing as the photo/preview (was hard-coded to 16:9 before,
            // which fought with the 3:4 viewfinder and caused the stretched/
            // "off angle" look during recording).
            val mrSizes = map.getOutputSizes(MediaRecorder::class.java)
                ?: map.getOutputSizes(SurfaceTexture::class.java)
                ?: arrayOf(Size(1280, 960))
            videoSize = chooseOptimalVideoSize(mrSizes)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { cameraDevice = camera; startPreviewSession() }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); cameraDevice = null }
            }, backgroundHandler)
        } catch (_: Exception) {}
    }

    private fun chooseOptimalPreviewSize(choices: Array<Size>?, width: Int, height: Int): Size {
        if (choices.isNullOrEmpty()) return Size(1280, 720)
        // The on-screen viewfinder is a 3:4 box, so the preview buffer must be
        // picked to match 4:3 (its landscape-sensor equivalent), NOT 16:9.
        // Picking 16:9 here was the root cause of preview/photo framing not
        // matching — the preview showed a much wider crop than what actually
        // got saved, which reads as "zooming" right after you tap the shutter.
        val targetRatio = 4f / 3f
        val suitable = choices.filter { it.width <= 1920 && it.height <= 1080 }
        val list = if (suitable.isNotEmpty()) suitable else choices.toList()
        return list.minByOrNull {
            val w = it.width.toFloat()
            val h = it.height.toFloat()
            val ratio = if (w > h) w / h else h / w
            Math.abs(ratio - targetRatio)
        } ?: choices[0]
    }

    private fun chooseOptimalVideoSize(choices: Array<Size>?): Size {
        if (choices.isNullOrEmpty()) return Size(1280, 960)
        val targetRatio = 4f / 3f
        val capped = choices.filter { it.width <= 1920 && it.height <= 1440 }
        val pool = if (capped.isNotEmpty()) capped else choices.toList()
        return pool.minByOrNull {
            val w = it.width.toFloat(); val h = it.height.toFloat()
            val ratio = if (w > h) w / h else h / w
            Math.abs(ratio - targetRatio)
        } ?: choices[0]
    }

    private fun configureTransform(
        view: TextureView, viewWidth: Int, viewHeight: Int,
        previewSize: Size, isFrontCamera: Boolean = false
    ) {
        val matrix = Matrix()
        val viewRect = android.graphics.RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = android.graphics.RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = Math.max(
            viewHeight.toFloat() / previewSize.width,
            viewWidth.toFloat() / previewSize.height
        )
        matrix.postScale(scale, scale, centerX, centerY)
        // Mirror the LIVE preview for the front camera so framing yourself
        // feels natural (like a mirror). The saved photo is separately
        // mirrored in processCleanHighQualityImage() to match — previously
        // this parameter was unused, so the preview was never mirrored while
        // the saved photo always was, which showed as a wrong "camera angle".
        if (isFrontCamera) {
            matrix.postScale(-1f, 1f, centerX, centerY)
        }
        view.setTransform(matrix)
    }

    private fun startPreviewSession() {
        val device = cameraDevice ?: return
        val tv = textureView ?: return
        val texture = tv.surfaceTexture ?: return
        val reader = imageReader ?: return
        try {
            previewSize?.let { texture.setDefaultBufferSize(it.width, it.height) }
            val surface = Surface(texture)
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, bestAfMode)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                bestFpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                if (currentZoomRatio > 1.0f) applyZoomToBuilder(this, currentZoomRatio)
            }
            previewRequestBuilder = builder
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(surface, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try { session.setRepeatingRequest(builder.build(), null, backgroundHandler) } catch (_: Exception) {}
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                }, backgroundHandler
            )
        } catch (_: Exception) {}
    }

    fun setZoom(zoomRatio: Float) {
        val clamped = zoomRatio.coerceIn(1f, maxZoom)
        if (Math.abs(currentZoomRatio - clamped) < 0.02f) return
        currentZoomRatio = clamped
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        applyZoomToBuilder(builder, clamped)
        try { session.setRepeatingRequest(builder.build(), null, backgroundHandler) } catch (_: Exception) {}
    }

    /**
     * Applies [ratio] to a capture request builder using whichever zoom
     * mechanism the API level supports. Shared by preview, video recording,
     * and live pinch/drag zoom so all three stay perfectly in sync — previously
     * each had its own slightly different copy of this logic, which caused a
     * visible zoom "jump" right when a video recording started.
     */
    private fun applyZoomToBuilder(builder: CaptureRequest.Builder, ratio: Float) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio)
        } else {
            val rect = activeArraySize ?: return
            val cropW = (rect.width() / ratio).toInt()
            val cropH = (rect.height() / ratio).toInt()
            val left = (rect.width() - cropW) / 2
            val top = (rect.height() - cropH) / 2
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropW, top + cropH))
        }
    }

    fun setFlash(enabled: Boolean) {
        isFlashOn = enabled
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        try {
            builder.set(CaptureRequest.FLASH_MODE, if (enabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (_: Exception) {}
    }

    fun takePicture(onSuccess: (File) -> Unit, onError: (String) -> Unit) {
        val device = cameraDevice ?: run { onError("Camera inactive"); return }
        val reader = imageReader ?: run { onError("Reader inactive"); return }
        val session = captureSession ?: run { onError("Session inactive"); return }
        val builder = previewRequestBuilder

        fun proceed() {
            try { builder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE) } catch (_: Exception) {}
            runCaptureNow(device, reader, session, onSuccess, onError)
        }

        if (builder == null || bestAfMode == CaptureRequest.CONTROL_AF_MODE_OFF) {
            proceed()
            return
        }

        // Briefly confirm focus before capturing — avoids the soft/blurry shots
        // that happen when the shutter fires mid-scan. Capped at 300ms so it
        // never feels like a delay to the user.
        var settled = false
        val timeoutRunnable = Runnable { if (!settled) { settled = true; proceed() } }
        try {
            backgroundHandler?.postDelayed(timeoutRunnable, 300)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult
                ) {
                    if (settled) return
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    val converged = afState == null ||
                        afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ||
                        afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                        afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED
                    if (converged) {
                        settled = true
                        backgroundHandler?.removeCallbacks(timeoutRunnable)
                        proceed()
                    }
                }
            }, backgroundHandler)
        } catch (_: Exception) {
            if (!settled) { settled = true; proceed() }
        }
    }

    private fun runCaptureNow(
        device: CameraDevice, reader: ImageReader, session: CameraCaptureSession,
        onSuccess: (File) -> Unit, onError: (String) -> Unit
    ) {
        try {
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, bestAfMode)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                if (isFlashOn) set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                // DO NOT set JPEG_ORIENTATION — some HALs physically rotate pixels AND
                // write EXIF, causing double-rotation. We rotate manually in processing.
            }
            reader.setOnImageAvailableListener({ ir ->
                val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                val rawFile = File(cameraMediaDir(context), "cam_${System.currentTimeMillis()}.jpg")
                try {
                    // Do not duplicate a high-resolution JPEG in a ByteArray.
                    // Some 48MP cameras produce 15–30MB frames, which is enough
                    // to create a short but severe memory spike on mid-range phones.
                    val buffer = image.planes[0].buffer
                    FileOutputStream(rawFile).channel.use { channel ->
                        while (buffer.hasRemaining()) channel.write(buffer)
                    }
                } finally {
                    image.close()
                }
                val isFront = lensFacing == CameraCharacteristics.LENS_FACING_FRONT
                onSuccess(processCleanHighQualityImage(rawFile, isFront, sensorOrientation, currentZoomRatio))
            }, backgroundHandler)
            session.capture(captureBuilder.build(), null, backgroundHandler)
        } catch (e: Exception) { onError(e.message ?: "Capture error") }
    }

    /**
     * Starts video recording. Returns the output File immediately (recording is in progress).
     * Call stopVideoRecording() to finish and get final video file.
     */
    fun startVideoRecording(outputFile: File, isFrontCamera: Boolean): File? {
        val device = cameraDevice ?: return null
        val tv = textureView ?: return null
        val texture = tv.surfaceTexture ?: return null
        val vs = videoSize ?: Size(1280, 720)

        return try {
            // Close existing preview session before binding recorder surface
            try {
                captureSession?.stopRepeating()
                captureSession?.abortCaptures()
                captureSession?.close()
            } catch (_: Exception) {}
            captureSession = null

            videoOutputFile = outputFile
            recordingStartMs = System.currentTimeMillis()

            val camIdInt = currentCameraId?.toIntOrNull() ?: 0
            val profile = when {
                CamcorderProfile.hasProfile(camIdInt, CamcorderProfile.QUALITY_720P) -> CamcorderProfile.get(camIdInt, CamcorderProfile.QUALITY_720P)
                CamcorderProfile.hasProfile(camIdInt, CamcorderProfile.QUALITY_HIGH) -> CamcorderProfile.get(camIdInt, CamcorderProfile.QUALITY_HIGH)
                CamcorderProfile.hasProfile(camIdInt, CamcorderProfile.QUALITY_480P) -> CamcorderProfile.get(camIdInt, CamcorderProfile.QUALITY_480P)
                else -> null
            }

            val recorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            // Bitrate scaled to OUR chosen resolution (not the profile's), since
            // we no longer let the profile dictate video dimensions below.
            val baseArea = 1280 * 720
            val area = vs.width * vs.height
            val estimatedBitRate = (5_000_000L * area / baseArea).coerceIn(2_500_000L, 12_000_000L).toInt()

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                if (profile != null) {
                    setOutputFormat(profile.fileFormat)
                    setVideoEncoder(profile.videoCodec)
                    setAudioEncoder(profile.audioCodec)
                    setAudioEncodingBitRate(profile.audioBitRate.coerceAtMost(128000))
                    setAudioSamplingRate(profile.audioSampleRate)
                } else {
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44100)
                    setAudioEncodingBitRate(128000)
                }
                // Always use OUR 3:4-matched size, not the profile's fixed 16:9 —
                // this is what keeps recorded video framed the same as the photo
                // and the live preview.
                setVideoSize(vs.width, vs.height)
                setVideoFrameRate((profile?.videoFrameRate ?: 30).coerceIn(24, 30))
                setVideoEncodingBitRate(estimatedBitRate)
                setOutputFile(outputFile.absolutePath)
                setOrientationHint(sensorOrientation)
                prepare()
            }
            mediaRecorder = recorder

            // Buffer must match the size we actually told MediaRecorder to use —
            // previously this used the profile's 16:9 size while we intended a
            // 3:4 video, causing a size mismatch that stretched the preview.
            texture.setDefaultBufferSize(vs.width, vs.height)
            // Recompute the TextureView transform for the NEW buffer size —
            // previously this was never done when recording started, so the
            // preview kept using the still-photo transform and visibly
            // warped/stretched the instant you started recording.
            if (tv.width > 0 && tv.height > 0) {
                configureTransform(tv, tv.width, tv.height, vs, isFrontCamera)
            }
            val previewSurface = Surface(texture)
            val recorderSurface = recorder.surface

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(recorderSurface)
                set(CaptureRequest.CONTROL_AF_MODE, bestAfMode)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                bestFpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                // Same zoom helper used everywhere else — avoids the zoom "jump"
                // that happened when recording start used different crop math
                // than live pinch/drag zoom.
                if (currentZoomRatio > 1.0f) applyZoomToBuilder(this, currentZoomRatio)
            }
            previewRequestBuilder = builder

            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(previewSurface, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            recorder.start()
                            isVideoRecording = true
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        } catch (_: Exception) {
                            try {
                                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                                recorder.start()
                                isVideoRecording = true
                            } catch (_: Exception) {
                                isVideoRecording = false
                            }
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        isVideoRecording = false
                    }
                }, backgroundHandler
            )
            outputFile
        } catch (e: Exception) {
            try {
                mediaRecorder?.reset()
                mediaRecorder?.release()
            } catch (_: Exception) {}
            mediaRecorder = null
            isVideoRecording = false
            null
        }
    }

    /**
     * Stops video recording safely and returns the saved video File.
     */
    fun stopVideoRecording(): File? {
        val file = videoOutputFile
        try {
            if (isVideoRecording) {
                try {
                    captureSession?.stopRepeating()
                    captureSession?.abortCaptures()
                } catch (_: Exception) {}
                try {
                    mediaRecorder?.stop()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {} finally {
            try {
                mediaRecorder?.reset()
                mediaRecorder?.release()
            } catch (_: Exception) {}
            mediaRecorder = null
            isVideoRecording = false
            captureSession = null
            videoOutputFile = null
        }
        val tv = textureView
        if (tv != null && tv.isAvailable) {
            openCamera(tv.width, tv.height)
        }
        return if (file != null && file.exists() && file.length() > 5000) file else null
    }

    private fun closeCameraOnly() {
        try {
            if (isVideoRecording) { mediaRecorder?.stop(); mediaRecorder?.release(); mediaRecorder = null; isVideoRecording = false }
            captureSession?.close(); captureSession = null
            cameraDevice?.close(); cameraDevice = null
            imageReader?.close(); imageReader = null
        } catch (_: Exception) {}
    }

    fun release() {
        pendingReopenRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        pendingReopenRunnable = null
        closeCameraOnly()
        try { backgroundThread?.quitSafely(); backgroundThread = null; backgroundHandler = null; textureView = null } catch (_: Exception) {}
    }
}

/**
 * Photo processing: orientation fix + software zoom crop + front camera mirror.
 */
private fun processCleanHighQualityImage(
    file: File, isFrontCamera: Boolean, sensorOrientation: Int, zoomRatio: Float = 1.0f
): File {
    return try {
        val exif = ExifInterface(file.absolutePath)
        val exifOrientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        // Bound the raw decode for very high-resolution cameras. A 48MP ARGB
        // bitmap is ~190MB and can cause memory pressure/OOM on mid-range OEMs;
        // normal 12MP captures remain full resolution.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sampleSize = 1
        while (bounds.outWidth / sampleSize.toLong() * (bounds.outHeight / sampleSize.toLong()) > 12_000_000L) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inScaled = false
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val original = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return file

        // Raw JPEG from Camera2 is always in the sensor's native landscape orientation.
        // EXIF is NOT set (we removed JPEG_ORIENTATION from capture) so it will be NORMAL.
        // We always manually rotate by sensorOrientation, then mirror for front camera.
        // This is the most reliable approach across all Android devices/manufacturers.
        val matrix = Matrix()
        if (sensorOrientation != 0) matrix.postRotate(sensorOrientation.toFloat())
        // Mirror front camera AFTER rotation so saved selfie matches live preview
        if (isFrontCamera) matrix.postScale(-1f, 1f)

        var processed = if (!matrix.isIdentity)
            Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        else original

        // Center-crop to the SAME 3:4 aspect ratio shown in the viewfinder.
        // Without this, a sensor whose native/largest JPEG size isn't already
        // 3:4 (e.g. a 4:3 or wider sensor) saves noticeably more of the scene
        // than what was actually framed on screen — the extra content popping
        // in is what reads as the photo "zooming" right after you capture it.
        val targetRatio = 3f / 4f // width:height, portrait
        val curW = processed.width
        val curH = processed.height
        if (curW > 0 && curH > 0) {
            val curRatio = curW.toFloat() / curH.toFloat()
            var framedW = curW
            var framedH = curH
            if (curRatio > targetRatio) {
                framedW = (curH * targetRatio).toInt().coerceAtLeast(1)
            } else if (curRatio < targetRatio) {
                framedH = (curW / targetRatio).toInt().coerceAtLeast(1)
            }
            if (framedW < curW || framedH < curH) {
                val fx = ((curW - framedW) / 2).coerceIn(0, curW - framedW)
                val fy = ((curH - framedH) / 2).coerceIn(0, curH - framedH)
                val framed = Bitmap.createBitmap(processed, fx, fy, framedW, framedH)
                if (processed !== original) processed.recycle()
                processed = framed
            }
        }

        // Software zoom crop — applied on top of the correctly-framed image.
        if (zoomRatio > 1.05f) {
            val cropW = (processed.width / zoomRatio).toInt()
            val cropH = (processed.height / zoomRatio).toInt()
            val cropX = ((processed.width - cropW) / 2).coerceAtLeast(0)
            val cropY = ((processed.height - cropH) / 2).coerceAtLeast(0)
            if (cropW > 0 && cropH > 0 && cropX + cropW <= processed.width && cropY + cropH <= processed.height) {
                val zoomed = Bitmap.createBitmap(processed, cropX, cropY, cropW, cropH, null, true)
                if (processed !== original) processed.recycle()
                processed = zoomed
            }
        }

        FileOutputStream(file).use { out -> processed.compress(Bitmap.CompressFormat.JPEG, 100, out) }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            saveAttributes()
        }
        if (processed !== original && !original.isRecycled) original.recycle()
        file
    } catch (_: Exception) { file }
}
