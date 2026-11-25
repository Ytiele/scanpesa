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
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.scanpesa.app.automation.DebugLog
import com.scanpesa.app.automation.ScanPesaAccessibilityService
import com.scanpesa.app.data.Prefs
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
    val prefs = remember { Prefs(ctx) }
    var enabled by remember { mutableStateOf(TargetLauncher.isServiceEnabled(ctx)) }
    var last by remember { mutableStateOf(prefs.lastResult) }
    val running by ScanPesaAccessibilityService.connected.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        enabled = TargetLauncher.isServiceEnabled(ctx)
        last = prefs.lastResult
    }

    Scaffold(topBar = { ScreenTopBar("ScanPesa", null) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ServiceStatusCard(enabled, running = running, onEnable = { TargetLauncher.openAccessibilitySettings(ctx) })
            last?.let { LastResultCard(it) }
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
fun ServiceStatusCard(enabled: Boolean, running: Boolean = enabled, onEnable: () -> Unit) {
    val ok = enabled && running
    SectionCard(container = if (ok) GreenLight else Color(0xFFFFEBEE)) {
        Text(
            when {
                ok -> "✔  Auto-fill is ON"
                enabled -> "⚠  Auto-fill is ON but not running"
                else -> "✖  Auto-fill is OFF"
            },
            style = MaterialTheme.typography.titleLarge,
            color = if (ok) Color(0xFF00522A) else Danger,
            fontWeight = FontWeight.Bold,
        )
        Text(
            when {
                ok -> "The ScanPesa accessibility service is running."
                enabled -> "Your phone stopped the service (battery saver). Switch \"ScanPesa auto-fill\" OFF and ON again, " +
                    "and set ScanPesa's battery usage to Unrestricted in App info."
                else -> "Turn on the \"ScanPesa auto-fill\" accessibility service so ScanPesa can type the numbers into M-PESA for you."
            },
            color = Color(0xFF1B1B1B),
        )
        if (!ok) BigButton("Open Accessibility Settings", onClick = onEnable)
    }
}

@Composable
private fun LastResultCard(r: Prefs.LastResult) {
    val ctx = LocalContext.current
    val events by DebugLog.events.collectAsState()
    var open by remember { mutableStateOf(false) }
    SectionCard(container = if (r.ok) GreenLight else Color(0xFFFFF3E0)) {
        Text("Last auto-fill · ${DebugLog.timeStr(r.time)}", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF444444))
        Text(r.title, fontWeight = FontWeight.Bold, color = if (r.ok) Color(0xFF00522A) else Color(0xFF8A4B00))
        Text(r.detail, color = Color(0xFF1B1B1B))
        if (events.isNotEmpty()) {
            BigOutlinedButton(if (open) "Hide steps" else "Show steps", onClick = { open = !open })
            if (open) {
                events.take(15).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF1B1B1B)) }
                BigOutlinedButton("Share this log", onClick = {
                    val text = "ScanPesa auto-fill log\n${r.title}\n${r.detail}\n\n" + events.joinToString("\n")
                    ctx.startActivity(
                        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share log"),
                    )
                })
            }
        }
    }
}
