package com.scanpesa.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.scanpesa.app.R
import com.scanpesa.app.model.Candidate
import com.scanpesa.app.parser.MpesaParser
import com.scanpesa.app.ui.AppViewModel
import com.scanpesa.app.ui.BigButton
import com.scanpesa.app.ui.BigOutlinedButton
import com.scanpesa.app.ui.Green
import com.scanpesa.app.ui.GreenLight
import com.scanpesa.app.ui.SectionCard
import androidx.compose.ui.text.font.FontWeight
import com.scanpesa.app.ui.ScreenTopBar
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun ScanScreen(vm: AppViewModel, onDetected: () -> Unit, onManual: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    Scaffold(topBar = { ScreenTopBar("Scan", onBack) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (!granted) {
                Text("ScanPesa needs the camera to read M-PESA numbers. Text is read on the phone, offline.")
                BigButton("Allow camera", onClick = { launcher.launch(Manifest.permission.CAMERA) })
                BigOutlinedButton("Type manually", onClick = onManual)
            } else {
                CameraScanner(vm, onDetected, onManual)
            }
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
    var status by remember { mutableStateOf("Point at the Paybill, Till or phone number") }
    var live by remember { mutableStateOf<List<Candidate>>(emptyList()) }
    val doneFlag = remember { AtomicBoolean(false) }
    val providerFuture = remember { ProcessCameraProvider.getInstance(ctx) }

    fun accept(found: List<Candidate>) {
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
        val done = doneFlag
        val busy = AtomicBoolean(false)
        // Recent frame results. Handwriting OCR flickers, so a result counts once it was seen in
        // 2 of the last 8 frames (not necessarily consecutive).
        val recent = ArrayDeque<String>()
        val main = ContextCompat.getMainExecutor(ctx)

        @androidx.annotation.OptIn(ExperimentalGetImage::class)
        fun analyze(proxy: ImageProxy) {
            val media = proxy.image
            if (media == null || done.get() || !busy.compareAndSet(false, true)) { proxy.close(); return }
            val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val found: List<Candidate> = MpesaParser.parse(result.text)
                    val key = if (found.isEmpty()) "" else found.joinToString(";") { it.key }
                    recent.addLast(key)
                    while (recent.size > 8) recent.removeFirst()
                    if (found.isEmpty()) return@addOnSuccessListener
                    if (recent.count { it == key } >= 2) {
                        main.execute { accept(found) }
                    } else {
                        main.execute {
                            live = found
                            status = "Reading… hold steady, or tap Use this"
                        }
                    }
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

    Box(
        Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(24.dp)).border(2.dp, Green, RoundedCornerShape(24.dp)),
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Text(
            status,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0x99000000)).padding(10.dp),
        )
    }
    if (live.isNotEmpty()) {
        SectionCard(container = GreenLight) {
            Text("Seen: " + live.joinToString("  ·  ") { c ->
                (if (c.typeConfirmed) c.type.label else "Phone") + " " + c.number + (c.account?.let { " / $it" } ?: "")
            }, fontWeight = FontWeight.SemiBold, color = Color(0xFF1B1B1B))
            BigButton("Use this", onClick = { accept(live) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
        FilledIconToggleButton(
            checked = torchOn,
            enabled = hasFlash,
            onCheckedChange = { on -> torchOn = on; camera?.cameraControl?.enableTorch(on) },
            modifier = Modifier.size(64.dp),
        ) { Icon(painterResource(R.drawable.ic_flash), contentDescription = if (torchOn) "Torch off" else "Torch on") }
        BigOutlinedButton("Type manually", onClick = onManual, modifier = Modifier.weight(1f))
    }
    Text(
        "Tip: fill the frame with the sign. Use the torch in dim light.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun vibrate(ctx: Context) {
    val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    v?.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE))
}
