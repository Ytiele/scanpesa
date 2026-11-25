package com.scanpesa.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.scanpesa.app.R
import com.scanpesa.app.automation.TargetLauncher
import com.scanpesa.app.ui.BigButton
import com.scanpesa.app.ui.BigOutlinedButton
import com.scanpesa.app.ui.Danger
import com.scanpesa.app.ui.GreenLight
import com.scanpesa.app.ui.ScreenTopBar
import com.scanpesa.app.ui.SectionCard

@Composable
fun HomeScreen(onScan: () -> Unit, onManual: () -> Unit, onHistory: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(TargetLauncher.isServiceEnabled(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { enabled = TargetLauncher.isServiceEnabled(ctx) }

    Scaffold(topBar = { ScreenTopBar("ScanPesa", null) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ServiceStatusCard(enabled, onEnable = { TargetLauncher.openAccessibilitySettings(ctx) })
            Spacer(Modifier.height(4.dp))
            BigButton(
                "Scan M-PESA details", onClick = onScan, modifier = Modifier.height(96.dp),
                leading = { Icon(painterResource(R.drawable.ic_notification), null, Modifier.size(32.dp)) },
            )
            BigButton(
                "Type manually", onClick = onManual, container = MaterialTheme.colorScheme.secondary,
                leading = { Icon(painterResource(R.drawable.ic_keyboard), null, Modifier.size(28.dp)) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigOutlinedButton("History", onClick = onHistory, modifier = Modifier.weight(1f))
                BigOutlinedButton("Settings", onClick = onSettings, modifier = Modifier.weight(1f))
            }
            Text(
                "ScanPesa fills in the numbers only. You always type the amount and PIN yourself.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ServiceStatusCard(enabled: Boolean, onEnable: () -> Unit) {
    SectionCard(container = if (enabled) GreenLight else Color(0xFFFFEBEE)) {
        Text(
            if (enabled) "✔  Auto-fill is ON" else "✖  Auto-fill is OFF",
            style = MaterialTheme.typography.titleLarge,
            color = if (enabled) Color(0xFF00522A) else Danger,
            fontWeight = FontWeight.Bold,
        )
        Text(
            if (enabled) "The ScanPesa accessibility service is enabled."
            else "Turn on the \"ScanPesa auto-fill\" accessibility service so ScanPesa can type the numbers into M-PESA for you.",
            color = Color(0xFF1B1B1B),
        )
        if (!enabled) BigButton("Open Accessibility Settings", onClick = onEnable)
    }
}
