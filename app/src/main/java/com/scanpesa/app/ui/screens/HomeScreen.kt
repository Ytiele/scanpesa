package com.scanpesa.app.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.scanpesa.app.R
import com.scanpesa.app.automation.DebugLog
import com.scanpesa.app.automation.ScanPesaAccessibilityService
import com.scanpesa.app.automation.TargetLauncher
import com.scanpesa.app.data.Prefs
import com.scanpesa.app.ui.BigButton
import com.scanpesa.app.ui.Danger
import com.scanpesa.app.ui.Green
import com.scanpesa.app.ui.GreenLight
import com.scanpesa.app.ui.SectionCard
import com.scanpesa.app.ui.Space
import com.scanpesa.app.ui.StatusPill

/** Front screen: auto-fill status, one big Scan action, last result. Nothing else. */
@Composable
fun HomeScreen(onScan: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var enabled by remember { mutableStateOf(TargetLauncher.isServiceEnabled(ctx)) }
    var last by remember { mutableStateOf(prefs.lastResult) }
    val running by ScanPesaAccessibilityService.connected.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        enabled = TargetLauncher.isServiceEnabled(ctx)
        last = prefs.lastResult
    }
    val ok = enabled && running

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = Space.screen, vertical = Space.m),
    ) {
        Text("ScanPesa", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(Space.s))
        StatusPill(
            text = when {
                ok -> "Auto-fill on · SIM Toolkit"
                enabled -> "Auto-fill stopped · tap to fix"
                else -> "Auto-fill off · tap to turn on"
            },
            ok = ok,
            onClick = if (ok) null else ({ TargetLauncher.openAccessibilitySettings(ctx) }),
        )

        Spacer(Modifier.height(Space.xl))

        // Hero scan target
        Surface(
            onClick = onScan,
            shape = MaterialTheme.shapes.large,
            color = Green, // solid M-PESA green in light and dark mode (white text stays readable)
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        ) {
            Column(
                Modifier.fillMaxSize().padding(Space.l),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(112.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(R.drawable.ic_scan), null, Modifier.size(60.dp), tint = Color.White) }
                Spacer(Modifier.height(Space.l))
                Text("Scan M-PESA details", style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Space.xs))
                Text(
                    "Paybill · Till · Pochi · Phone",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(Space.l))
        last?.let { LastResultRow(it) }
        Spacer(Modifier.height(Space.m))
        Text(
            "ScanPesa fills in the numbers. You always enter the amount and PIN yourself.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One quiet line with the last auto-fill outcome; tap to see the steps. */
@Composable
private fun LastResultRow(r: Prefs.LastResult) {
    val ctx = LocalContext.current
    val events by DebugLog.events.collectAsState()
    var open by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable { open = !open }.padding(horizontal = Space.m, vertical = Space.s + 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(if (r.ok) R.drawable.ic_check else R.drawable.ic_warning), null,
                    tint = if (r.ok) MaterialTheme.colorScheme.primary else Color(0xFFE07A00),
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(Space.s))
                Column(Modifier.weight(1f)) {
                    Text(r.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        "Last auto-fill · ${DebugLog.timeStr(r.time)}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(top = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(r.detail, style = MaterialTheme.typography.bodyMedium)
                    events.take(12).forEach {
                        Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (events.isNotEmpty()) TextButton(onClick = {
                        val text = "ScanPesa auto-fill log\n${r.title}\n${r.detail}\n\n" + events.joinToString("\n")
                        ctx.startActivity(
                            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share log"),
                        )
                    }) { Text("Share log") }
                }
            }
        }
    }
}

/** Used by onboarding: full status with a button to fix it. */
@Composable
fun ServiceStatusCard(enabled: Boolean, running: Boolean = enabled, onEnable: () -> Unit) {
    val ok = enabled && running
    SectionCard(container = if (ok) GreenLight else Color(0xFFFFEBEE)) {
        Text(
            when {
                ok -> "Auto-fill is on"
                enabled -> "Auto-fill is on but not running"
                else -> "Auto-fill is off"
            },
            style = MaterialTheme.typography.titleLarge,
            color = if (ok) Color(0xFF00522A) else Danger,
        )
        Text(
            when {
                ok -> "The ScanPesa accessibility service is running."
                enabled -> "Your phone stopped the service. Switch \"ScanPesa auto-fill\" off and on again, and set ScanPesa's battery use to Unrestricted."
                else -> "Turn on \"ScanPesa auto-fill\" so ScanPesa can type the numbers into SIM Toolkit for you."
            },
            color = Color(0xFF1B1B1B),
        )
        if (!ok) BigButton("Open Accessibility settings", onClick = onEnable)
    }
}
