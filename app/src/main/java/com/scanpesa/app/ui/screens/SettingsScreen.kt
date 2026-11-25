package com.scanpesa.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.scanpesa.app.BuildConfig
import com.scanpesa.app.automation.FlowRepository
import com.scanpesa.app.automation.TargetLauncher
import com.scanpesa.app.model.Target
import com.scanpesa.app.ui.AppViewModel
import com.scanpesa.app.ui.BigButton
import com.scanpesa.app.ui.BigOutlinedButton
import com.scanpesa.app.ui.RadioRow
import com.scanpesa.app.ui.ScreenTopBar
import com.scanpesa.app.ui.SectionCard

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, onDebug: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = vm.prefs
    var defaultTarget by remember { mutableStateOf(prefs.defaultTarget) }
    var sim by remember { mutableIntStateOf(prefs.simSlot) }
    var vibrate by remember { mutableStateOf(prefs.vibrateOnDetect) }
    var debugUnlocked by remember { mutableStateOf(prefs.debugUnlocked) }
    var debug by remember { mutableStateOf(prefs.debugMode) }
    var taps by remember { mutableIntStateOf(0) }
    var hasOverride by remember { mutableStateOf(FlowRepository.hasOverride(ctx)) }
    var showDisclosure by remember { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }.getOrNull()
        val err = if (text == null) "Could not read the file" else FlowRepository.importOverride(ctx, text)
        hasOverride = FlowRepository.hasOverride(ctx)
        Toast.makeText(ctx, err ?: "Flows imported. Turn the accessibility service off and on to apply package changes.", Toast.LENGTH_LONG).show()
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(FlowRepository.bundledJson(ctx).toByteArray()) }
        }.onSuccess { Toast.makeText(ctx, "Saved built-in flows.json", Toast.LENGTH_SHORT).show() }
    }

    Scaffold(topBar = { ScreenTopBar("Settings", onBack) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard {
                Text("Default target", style = MaterialTheme.typography.titleMedium)
                RadioRow(defaultTarget == null, "Last one I used") { defaultTarget = null; prefs.defaultTarget = null }
                Target.entries.forEach { t ->
                    RadioRow(defaultTarget == t, t.label, t.subtitle) { defaultTarget = t; prefs.defaultTarget = t }
                }
            }
            SectionCard {
                Text("SIM Toolkit: SIM slot", style = MaterialTheme.typography.titleMedium)
                Text("On dual-SIM phones, pick the slot with your Safaricom line.", style = MaterialTheme.typography.bodyMedium)
                RadioRow(sim == 1, "SIM 1") { sim = 1; prefs.simSlot = 1 }
                RadioRow(sim == 2, "SIM 2") { sim = 2; prefs.simSlot = 2 }
            }
            SectionCard {
                SwitchRow("Vibrate when details are found", vibrate) { vibrate = it; prefs.vibrateOnDetect = it }
            }
            SectionCard {
                Text("Auto-fill", style = MaterialTheme.typography.titleMedium)
                Text(if (TargetLauncher.isServiceEnabled(ctx)) "Service: ON" else "Service: OFF")
                BigOutlinedButton("Open Accessibility Settings", onClick = { TargetLauncher.openAccessibilitySettings(ctx) })
                BigOutlinedButton(if (showDisclosure) "Hide disclosure" else "What does auto-fill do?", onClick = { showDisclosure = !showDisclosure })
                if (showDisclosure) AccessibilityDisclosureCard()
            }
            SectionCard {
                Text("Menu flows (flows.json)", style = MaterialTheme.typography.titleMedium)
                Text(if (hasOverride) "Using your imported flows file." else "Using the built-in flows.")
                BigOutlinedButton("Save built-in flows.json…", onClick = { exporter.launch("flows.json") })
                BigOutlinedButton("Import flows.json…", onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) })
                if (hasOverride) BigOutlinedButton("Reset to built-in flows", onClick = {
                    FlowRepository.resetOverride(ctx); hasOverride = false
                })
            }
            SectionCard {
                BigOutlinedButton("Clear history", onClick = {
                    vm.clearHistory(); Toast.makeText(ctx, "History cleared", Toast.LENGTH_SHORT).show()
                })
            }
            if (debugUnlocked) {
                SectionCard {
                    Text("Debug", style = MaterialTheme.typography.titleMedium)
                    SwitchRow("Debug mode: dump screens of the target apps", debug) { debug = it; prefs.debugMode = it }
                    Text(
                        "Writes the on-screen labels of the M-PESA app, SIM Toolkit and My Safaricom to logcat (tag ScanPesaDump) " +
                            "and to the Debug screen. PIN/password fields are never included.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    BigButton("Open Debug screen", onClick = onDebug)
                }
            }
            Text(
                "ScanPesa ${BuildConfig.VERSION_NAME}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable {
                    taps++
                    if (!debugUnlocked && taps >= 7) {
                        debugUnlocked = true; prefs.debugUnlocked = true
                        Toast.makeText(ctx, "Debug options unlocked", Toast.LENGTH_SHORT).show()
                    }
                }.padding(12.dp),
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
