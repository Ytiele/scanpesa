package com.scanpesa.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Size as CamSize
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.scanpesa.app.R
import com.scanpesa.app.model.Candidate
import com.scanpesa.app.parser.Box as PBox
import com.scanpesa.app.parser.MpesaParser
import com.scanpesa.app.parser.ScanHighlights
import com.scanpesa.app.ui.AppViewModel
import com.scanpesa.app.ui.BigButton
import com.scanpesa.app.ui.BigOutlinedButton
import com.scanpesa.app.ui.Green
import com.scanpesa.app.ui.PhotoReader
import com.scanpesa.app.ui.ScreenTopBar
import com.scanpesa.app.ui.Space
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One outline drawn on the live preview (view pixels). */
private data class Outline(val box: PBox, val kind: ScanHighlights.Kind)

/** 4:3 everywhere so preview, live analysis and the photo show the same scene. */
private fun selector(target: CamSize, rule: Int) = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(ResolutionStrategy(target, rule))
    .build()

@Composable
fun ScanScreen(vm: AppViewModel, onPhotoRead: () -> Unit, onManual: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var reading by remember { mutableStateOf<Bitmap?>(null) } // photo being read

    fun read(bitmap: Bitmap?) {
        if (bitmap == null) { Toast.makeText(ctx, "Couldn't open that photo", Toast.LENGTH_SHORT).show(); return }
        reading = bitmap
        PhotoReader.read(bitmap) { scan ->
            reading = null
            if (scan == null) { Toast.makeText(ctx, "Couldn't read that photo", Toast.LENGTH_SHORT).show(); return@read }
            if (vm.prefs.vibrateOnDetect && scan.tokens.isNotEmpty()) vibrate(ctx)
            vm.onPhotoRead(scan)
            onPhotoRead()
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) read(PhotoReader.fromUri(ctx, uri))
    }
    val openGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScreenTopBar("Scan", onBack, action = { TextButton(onClick = onManual) { Text("Type it in") } })
        if (!granted) {
            Column(Modifier.padding(horizontal = Space.screen), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Text(
                    "ScanPesa needs the camera to read M-PESA numbers. Text is read on the phone, offline.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                BigButton("Allow camera", onClick = { launcher.launch(Manifest.permission.CAMERA) })
                BigOutlinedButton("Pick a photo instead", onClick = openGallery)
            }
        } else {
            CameraScanner(vm, reading, onPhoto = ::read, onGallery = openGallery)
        }
    }
}

@Composable
private fun CameraScanner(vm: AppViewModel, reading: Bitmap?, onPhoto: (Bitmap?) -> Unit, onGallery: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // ~12 MP: plenty for signs across a room, quick to capture.
            .setResolutionSelector(selector(CamSize(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
    }
    var torchOn by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var focusAt by remember { mutableStateOf<Offset?>(null) }
    var live by remember { mutableStateOf<List<Candidate>>(emptyList()) }
    var steady by remember { mutableStateOf(false) }
    var outlines by remember { mutableStateOf<List<Outline>>(emptyList()) }
    val shooting = remember { AtomicBoolean(false) }
    val providerFuture = remember { ProcessCameraProvider.getInstance(ctx) }
    val autoCapture = remember { vm.prefs.autoCapture }
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }

    fun shoot() {
        if (!shooting.compareAndSet(false, true)) return
        imageCapture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = runCatching { PhotoReader.fromCapture(image) }.getOrNull()
                image.close()
                ContextCompat.getMainExecutor(ctx).execute {
                    runCatching { providerFuture.get().unbindAll() } // freeze while reading
                    onPhoto(bmp)
                }
            }

            override fun onError(e: ImageCaptureException) {
                shooting.set(false)
                ContextCompat.getMainExecutor(ctx).execute {
                    Toast.makeText(ctx, "Couldn't take the photo, try again", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    LaunchedEffect(focusAt) { if (focusAt != null) { kotlinx.coroutines.delay(1200); focusAt = null } }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val busy = AtomicBoolean(false)
        // A reading is "steady" once the same result was seen in 2 of the last 8 frames.
        val recent = ArrayDeque<String>()
        val main = ContextCompat.getMainExecutor(ctx)

        @androidx.annotation.OptIn(ExperimentalGetImage::class)
        fun analyze(proxy: ImageProxy) {
            val media = proxy.image
            if (media == null || shooting.get() || !busy.compareAndSet(false, true)) { proxy.close(); return }
            val rotation = proxy.imageInfo.rotationDegrees
            val upright = rotation == 90 || rotation == 270
            val imgW = if (upright) proxy.height else proxy.width
            val imgH = if (upright) proxy.width else proxy.height
            recognizer.process(InputImage.fromMediaImage(media, rotation))
                .addOnSuccessListener { result -> // main thread
                    val found = MpesaParser.parse(result.text)
                    val key = if (found.isEmpty()) "" else found.joinToString(";") { it.key }
                    recent.addLast(key)
                    while (recent.size > 8) recent.removeFirst()
                    val isSteady = found.isNotEmpty() && recent.count { it == key } >= 2
                    val vw = previewView.width; val vh = previewView.height
                    outlines = if (found.isEmpty()) emptyList() else result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                        val kind = ScanHighlights.classify(line.text, found) ?: return@mapNotNull null
                        val r = line.boundingBox ?: return@mapNotNull null
                        Outline(
                            ScanHighlights.mapToView(PBox(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()), imgW, imgH, vw, vh),
                            kind,
                        )
                    }
                    live = found
                    steady = isSteady
                    if (isSteady && autoCapture) shoot()
                }
                .addOnCompleteListener { busy.set(false); proxy.close() }
        }

        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder()
                .setResolutionSelector(selector(CamSize(1600, 1200), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build().also { it.surfaceProvider = previewView.surfaceProvider }
            // 1600x1200 live frames (was 640x480): the boxes light up from much further away.
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector(CamSize(1600, 1200), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, ::analyze) }
            provider.unbindAll()
            camera = runCatching {
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, imageCapture)
            }.getOrElse {
                // Some phones can't run three streams at once: keep the photo, drop the live boxes.
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            }
        }, main)

        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            recognizer.close()
            executor.shutdown()
        }
    }

    val frameColor = if (steady) Green else Color.White.copy(alpha = 0.45f)
    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = Space.screen)) {
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(MaterialTheme.shapes.large)
                .border(2.dp, frameColor, MaterialTheme.shapes.large)
                .background(Color.Black),
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            // Live outlines + gestures (pinch to zoom, tap to focus)
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(camera) {
                        detectTransformGestures { _, _, zoomChange, _ ->
                            val cam = camera ?: return@detectTransformGestures
                            val st = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                            val z = (st.zoomRatio * zoomChange).coerceIn(st.minZoomRatio, st.maxZoomRatio)
                            cam.cameraControl.setZoomRatio(z)
                            zoom = z
                        }
                    }
                    .pointerInput(camera) {
                        detectTapGestures { pos ->
                            val cam = camera ?: return@detectTapGestures
                            val point = previewView.meteringPointFactory.createPoint(pos.x, pos.y)
                            cam.cameraControl.startFocusAndMetering(
                                FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                                    .setAutoCancelDuration(4, TimeUnit.SECONDS).build(),
                            )
                            focusAt = pos
                        }
                    },
            ) {
                val pad = 6.dp.toPx()
                val r = CornerRadius(10.dp.toPx())
                outlines.forEach { o ->
                    val tl = Offset(o.box.left - pad, o.box.top - pad)
                    val sz = Size(o.box.right - o.box.left + 2 * pad, o.box.bottom - o.box.top + 2 * pad)
                    when (o.kind) {
                        ScanHighlights.Kind.VALUE -> {
                            drawRoundRect(Green.copy(alpha = if (steady) 0.22f else 0.12f), tl, sz, r)
                            drawRoundRect(Green, tl, sz, r, style = Stroke((if (steady) 4.dp else 2.dp).toPx()))
                        }
                        ScanHighlights.Kind.KEYWORD -> drawRoundRect(Color.White.copy(alpha = 0.7f), tl, sz, r, style = Stroke(1.5.dp.toPx()))
                    }
                }
                focusAt?.let { drawCircle(Color.White, radius = 36.dp.toPx(), center = it, style = Stroke(2.dp.toPx())) }
            }
            // Status chip
            Text(
                when {
                    reading != null -> "Reading photo…"
                    live.isEmpty() -> "Point at the sign and tap the shutter"
                    !steady -> "Numbers found. Tap the shutter"
                    else -> "Looks good. Tap the shutter"
                },
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = Space.s)
                    .clip(CircleShape).background(Color(0x99000000)).padding(horizontal = 14.dp, vertical = 8.dp),
            )
            if (zoom > 1.05f) {
                Text(
                    "%.1f×".format(zoom), color = Color.White, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Space.s)
                        .clip(CircleShape).background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            // The captured photo while it's being read
            reading?.let { bmp ->
                Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }

        Spacer(Modifier.height(Space.s))
        Text(
            if (live.isEmpty()) "Works from a normal distance · pinch to zoom · tap to focus" else ScanHighlights.summary(live),
            style = if (live.isEmpty()) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
            color = if (steady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Space.s))

        // Controls: gallery · shutter · torch
        Row(
            Modifier.fillMaxWidth().padding(bottom = Space.m),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onGallery, modifier = Modifier.size(56.dp)) {
                Icon(painterResource(R.drawable.ic_photo), contentDescription = "Pick a photo", modifier = Modifier.size(26.dp))
            }
            Shutter(busy = reading != null, steady = steady, onClick = ::shoot)
            val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
            FilledIconToggleButton(
                checked = torchOn,
                enabled = hasFlash,
                onCheckedChange = { on -> torchOn = on; camera?.cameraControl?.enableTorch(on) },
                modifier = Modifier.size(56.dp),
            ) { Icon(painterResource(R.drawable.ic_flash), contentDescription = if (torchOn) "Torch off" else "Torch on") }
        }
    }
}

/** Round shutter. Always works (the photo is read in full); glows green when the live view is steady. */
@Composable
private fun Shutter(busy: Boolean, steady: Boolean, onClick: () -> Unit) {
    val fill by animateFloatAsState(if (steady) 1f else 0.75f, label = "shutter")
    Box(
        Modifier.size(84.dp).clip(CircleShape).border(4.dp, if (steady) Green else Color.White.copy(alpha = 0.8f), CircleShape)
            .clickable(enabled = !busy, onClickLabel = "Take photo", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(66.dp).clip(CircleShape).background(Green.copy(alpha = fill)))
    }
}

private fun vibrate(ctx: Context) {
    val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    v?.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE))
}
