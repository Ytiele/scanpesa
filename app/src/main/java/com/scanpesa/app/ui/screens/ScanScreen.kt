package com.scanpesa.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.scanpesa.app.ui.ScreenTopBar
import com.scanpesa.app.ui.Space
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One outline drawn on the preview (view pixels). */
private data class Outline(val box: PBox, val kind: ScanHighlights.Kind)

@Composable
fun ScanScreen(vm: AppViewModel, onDetected: () -> Unit, onManual: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScreenTopBar("Scan", onBack)
        if (!granted) {
            Column(Modifier.padding(horizontal = Space.screen), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Text(
                    "ScanPesa needs the camera to read M-PESA numbers. Text is read on the phone, offline.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                BigButton("Allow camera", onClick = { launcher.launch(Manifest.permission.CAMERA) })
                BigOutlinedButton("Type manually", onClick = onManual)
            }
        } else {
            CameraScanner(vm, onDetected, onManual)
        }
    }
}

@Composable
private fun CameraScanner(vm: AppViewModel, onDetected: () -> Unit, onManual: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf<List<Candidate>>(emptyList()) }
    var steady by remember { mutableStateOf(false) }
    var outlines by remember { mutableStateOf<List<Outline>>(emptyList()) }
    val doneFlag = remember { AtomicBoolean(false) }
    val providerFuture = remember { ProcessCameraProvider.getInstance(ctx) }
    val autoCapture = remember { vm.prefs.autoCapture }

    fun capture(found: List<Candidate>) {
        if (found.isEmpty()) {
            Toast.makeText(ctx, "No M-PESA details seen yet. Fill the frame with the sign.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!doneFlag.compareAndSet(false, true)) return
        val frame = previewView.bitmap
        runCatching { providerFuture.get().unbindAll() } // freeze
        if (vm.prefs.vibrateOnDetect) vibrate(ctx)
        vm.onScanned(found, frame)
        onDetected()
    }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val busy = AtomicBoolean(false)
        // A reading is "steady" once the same result was seen in 2 of the last 8 frames
        // (handwriting OCR flickers, so they needn't be consecutive).
        val recent = ArrayDeque<String>()
        val main = ContextCompat.getMainExecutor(ctx)

        @androidx.annotation.OptIn(ExperimentalGetImage::class)
        fun analyze(proxy: ImageProxy) {
            val media = proxy.image
            if (media == null || doneFlag.get() || !busy.compareAndSet(false, true)) { proxy.close(); return }
            val rotation = proxy.imageInfo.rotationDegrees
            val upright = rotation == 90 || rotation == 270
            val imgW = if (upright) proxy.height else proxy.width
            val imgH = if (upright) proxy.width else proxy.height
            val image = InputImage.fromMediaImage(media, rotation)
            recognizer.process(image)
                .addOnSuccessListener { result -> // main thread
                    val found = MpesaParser.parse(result.text)
                    val key = if (found.isEmpty()) "" else found.joinToString(";") { it.key }
                    recent.addLast(key)
                    while (recent.size > 8) recent.removeFirst()
                    val isSteady = found.isNotEmpty() && recent.count { it == key } >= 2

                    // Outline the lines that carry the numbers (and nearby keywords).
                    val vw = previewView.width; val vh = previewView.height
                    outlines = if (found.isEmpty()) emptyList() else result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                        val kind = ScanHighlights.classify(line.text, found) ?: return@mapNotNull null
                        val r = line.boundingBox ?: return@mapNotNull null
                        val b = ScanHighlights.mapToView(
                            PBox(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()), imgW, imgH, vw, vh,
                        )
                        Outline(b, kind)
                    }
                    live = found
                    steady = isSteady
                    if (isSteady && autoCapture) capture(found)
                }
                .addOnCompleteListener { busy.set(false); proxy.close() }
        }

        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, ::analyze) }
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, main)

        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            recognizer.close()
            executor.shutdown()
        }
    }

    val frameColor = if (steady) Green else Color.White.copy(alpha = 0.55f)
    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = Space.screen)) {
        // Camera with live outlines
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(MaterialTheme.shapes.large)
                .border(2.dp, frameColor, MaterialTheme.shapes.large)
                .background(Color.Black),
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val pad = 6.dp.toPx()
                outlines.forEach { o ->
                    val tl = Offset(o.box.left - pad, o.box.top - pad)
                    val size = Size(o.box.right - o.box.left + 2 * pad, o.box.bottom - o.box.top + 2 * pad)
                    when (o.kind) {
                        ScanHighlights.Kind.VALUE -> {
                            drawRoundRect(Green.copy(alpha = if (steady) 0.22f else 0.12f), tl, size, CornerRadius(10.dp.toPx()))
                            drawRoundRect(
                                Green, tl, size, CornerRadius(10.dp.toPx()),
                                style = Stroke(width = (if (steady) 4.dp else 2.dp).toPx()),
                            )
                        }
                        ScanHighlights.Kind.KEYWORD -> drawRoundRect(
                            Color.White.copy(alpha = 0.7f), tl, size, CornerRadius(10.dp.toPx()),
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }
                }
            }
            // Status pill inside the camera
            Text(
                when {
                    live.isEmpty() -> "Point at the Paybill, Till or phone number"
                    !steady -> "Hold steady…"
                    autoCapture -> "Got it"
                    else -> "Looks good. Tap the shutter"
                },
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = Space.s)
                    .clip(CircleShape).background(Color(0x99000000)).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        // What a capture will use
        Spacer(Modifier.height(Space.s))
        Text(
            if (live.isEmpty()) " " else ScanHighlights.summary(live),
            style = MaterialTheme.typography.titleMedium,
            color = if (steady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Space.s))

        // Controls: torch · shutter · type manually
        Row(
            Modifier.fillMaxWidth().padding(bottom = Space.m),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
            FilledIconToggleButton(
                checked = torchOn,
                enabled = hasFlash,
                onCheckedChange = { on -> torchOn = on; camera?.cameraControl?.enableTorch(on) },
                modifier = Modifier.size(56.dp),
            ) { Icon(painterResource(R.drawable.ic_flash), contentDescription = if (torchOn) "Torch off" else "Torch on") }

            Shutter(ready = live.isNotEmpty(), steady = steady) { capture(live) }

            IconButton(onClick = onManual, modifier = Modifier.size(56.dp)) {
                Icon(
                    painterResource(R.drawable.ic_keyboard), contentDescription = "Type manually",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/** Round camera shutter: grey ring when nothing is seen, green when a steady reading is ready. */
@Composable
private fun Shutter(ready: Boolean, steady: Boolean, onClick: () -> Unit) {
    val fill by animateFloatAsState(if (steady) 1f else if (ready) 0.55f else 0.2f, label = "shutter")
    val ring = if (steady) Green else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.size(84.dp).clip(CircleShape).border(4.dp, ring, CircleShape)
            .clickable(onClickLabel = "Capture", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(Green.copy(alpha = fill)))
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
