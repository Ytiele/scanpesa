package com.scanpesa.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.scanpesa.app.automation.TargetLauncher
import com.scanpesa.app.ui.BigButton
import com.scanpesa.app.ui.BigOutlinedButton
import com.scanpesa.app.ui.Disclosure
import com.scanpesa.app.ui.GreenLight
import com.scanpesa.app.ui.SectionCard

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (page) {
            0 -> CameraPage(onNext = { page = 1 })
            else -> AccessibilityPage(onDone = onDone)
        }
    }
}

@Composable
private fun CameraPage(onNext: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onNext() }
    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok && Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else if (ok) onNext()
    }
    Text("Welcome to ScanPesa", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text(
        "Point your camera at an M-PESA sign, sticker or receipt. ScanPesa reads the Paybill, Till, " +
            "Pochi or phone number and fills it in for you in M-PESA. You always type the amount and PIN yourself.",
        style = MaterialTheme.typography.bodyLarge,
    )
    SectionCard(container = GreenLight) {
        Text("Step 1 of 2: Camera", style = MaterialTheme.typography.titleLarge)
        Text(
            "ScanPesa needs the camera to read the numbers. Text is read on your phone and is never uploaded. " +
                "It will also ask to show notifications, so it can tell you when to enter the amount and PIN.",
        )
    }
    Spacer(Modifier.height(8.dp))
    if (granted) BigButton("Continue", onClick = onNext)
    else BigButton("Allow camera", onClick = { camLauncher.launch(Manifest.permission.CAMERA) })
    BigOutlinedButton("Skip for now", onClick = onNext)
}

@Composable
private fun AccessibilityPage(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(TargetLauncher.isServiceEnabled(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { enabled = TargetLauncher.isServiceEnabled(ctx) }

    Text("Step 2 of 2: Auto-fill", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    AccessibilityDisclosureCard()
    ServiceStatusCard(enabled = enabled, onEnable = { TargetLauncher.openAccessibilitySettings(ctx) })
    Text(
        "In Accessibility settings, open \"Installed apps\" or \"Downloaded apps\", tap \"ScanPesa auto-fill\" and switch it on.",
        style = MaterialTheme.typography.bodyMedium,
    )
    BigButton(if (enabled) "Finish" else "Finish without auto-fill", onClick = onDone)
}

@Composable
fun AccessibilityDisclosureCard() {
    SectionCard {
        Text(Disclosure.TITLE, style = MaterialTheme.typography.titleLarge)
        Disclosure.POINTS.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
}
