package com.scanpesa.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scanpesa.app.automation.DebugLog
import com.scanpesa.app.automation.DumpEntry
import com.scanpesa.app.ui.BigOutlinedButton
import com.scanpesa.app.ui.ScreenTopBar
import com.scanpesa.app.ui.SectionCard

@Composable
fun DebugScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dumps by DebugLog.dumps.collectAsState()
    val events by DebugLog.events.collectAsState()
    var expanded by remember { mutableStateOf<Long?>(null) }

    fun share(d: DumpEntry) {
        val text = "${d.packageName} ${d.event} @ ${DebugLog.timeStr(d.time)}\n" + d.lines.joinToString("\n")
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share dump"))
    }

    Scaffold(topBar = { ScreenTopBar("Debug", onBack) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Open the M-PESA app / SIM Toolkit / My Safaricom with Debug mode on, go through the menus, then come back here. " +
                        "Each screen's nodes are listed below (also in logcat: adb logcat -s ScanPesaDump). Copy the exact text into flows.json.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigOutlinedButton("Clear", onClick = { DebugLog.clear() }, modifier = Modifier.weight(1f))
                }
            }
            item {
                SectionCard {
                    Text("Automation log", fontWeight = FontWeight.Bold)
                    if (events.isEmpty()) Text("No events yet.")
                    events.take(30).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                }
            }
            items(dumps, key = { it.time }) { d ->
                SectionCard(Modifier.clickable { expanded = if (expanded == d.time) null else d.time }) {
                    Text("${DebugLog.timeStr(d.time)}  ${d.packageName}", fontWeight = FontWeight.Bold)
                    Text("${d.event} · ${d.lines.size} nodes · tap to ${if (expanded == d.time) "collapse" else "expand"}", fontSize = 13.sp)
                    if (expanded == d.time) {
                        Column(Modifier.horizontalScroll(rememberScrollState())) {
                            d.lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, softWrap = false) }
                        }
                        BigOutlinedButton("Share this dump", onClick = { share(d) })
                    }
                }
            }
        }
    }
}
