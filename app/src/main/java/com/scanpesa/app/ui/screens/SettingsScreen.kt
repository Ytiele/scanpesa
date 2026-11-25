package com.scanpesa.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.scanpesa.app.BuildConfig
import com.scanpesa.app.R
import com.scanpesa.app.automation.FlowRepository
import com.scanpesa.app.automation.ScanPesaAccessibilityService
import com.scanpesa.app.automation.TargetLauncher
import com.scanpesa.app.model.Target
import com.scanpesa.app.ui.AppViewModel
import com.scanpesa.app.ui.Disclosure
import com.scanpesa.app.ui.RowDivider
import com.scanpesa.app.ui.RowGroup
import com.scanpesa.app.ui.ScreenTopBar
import com.scanpesa.app.ui.SectionLabel
import com.scanpesa.app.ui.SettingsRow
import com.scanpesa.app.ui.Space
import com.scanpesa.app.ui.SwitchRow

// ======================================================================= General (tab)

@Composable
fun SettingsScreen(vm: AppViewModel, onAdvanced: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = vm.prefs
    var sim by remember { mutableIntStateOf(prefs.simSlot) }
    var vibrate by remember { mutableStateOf(prefs.vibrateOnDetect) }
    var showDisclosure by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(TargetLauncher.isServiceEnabled(ctx)) }
    val running by ScanPesaAccessibilityService.connected.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { enabled = TargetLauncher.isServiceEnabled(ctx) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Settings", null)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = Space.screen, end = Space.screen, bottom = Space.l),
        ) {
            SectionLabel("General", Modifier.padding(top = 0.dp))
            RowGroup {
                SettingsRow(
                    "Auto-fill",
                    subtitle = when {
                        enabled && running -> "On"
                        enabled -> "Stopped by the phone · tap to fix"
                        else -> "Off · tap to turn on"
                    },
                    icon = R.drawable.ic_tune,
                    onClick = { TargetLauncher.openAccessibilitySettings(ctx) },
                )
                RowDivider()
                SettingsRow(
                    "What auto-fill does",
                    subtitle = "What it reads, types and never touches",
                    onClick = { showDisclosure = !showDisclosure },
                )
                AnimatedVisibility(showDisclosure) {
                    Column(Modifier.padding(horizontal = Space.m, vertical = Space.xs), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Disclosure.POINTS.forEach {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(Space.xs))
                    }
                }
            }

            SectionLabel("SIM Toolkit")
            RowGroup {
                Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text("Safaricom line", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "On dual-SIM phones, pick the slot with your Safaricom SIM.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(1, 2).forEachIndexed { i, slot ->
                            SegmentedButton(
                                selected = sim == slot,
                                onClick = { sim = slot; prefs.simSlot = slot; ScanPesaAccessibilityService.instance?.refreshWatched() },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("SIM $slot") }
                        }
                    }
                }
            }

            SectionLabel("Scanning")
            RowGroup {
                SwitchRow("Vibrate on capture", checked = vibrate) { vibrate = it; prefs.vibrateOnDetect = it }
            }

            SectionLabel("More")
            RowGroup {
                SettingsRow("Advanced", subtitle = "Apps used, menu flows, history, debugging", onClick = onAdvanced)
            }

            Spacer(Modifier.height(Space.l))
            Text(
                "ScanPesa ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Space.xs),
            )
        }
    }
}

// ======================================================================= Advanced

@Composable
fun AdvancedSettingsScreen(vm: AppViewModel, onBack: () -> Unit, onDebug: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = vm.prefs
    var debug by remember { mutableStateOf(prefs.debugMode) }
    var hasOverride by remember { mutableStateOf(FlowRepository.hasOverride(ctx)) }
    var resolved by remember { mutableStateOf(Target.available.associateWith { TargetLauncher.resolvePackage(ctx, it, prefs.simSlot) }) }
    var picking by remember { mutableStateOf<Target?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var defaultTarget by remember { mutableStateOf(prefs.defaultTarget) }

    fun refreshApps() {
        resolved = Target.available.associateWith { TargetLauncher.resolvePackage(ctx, it, prefs.simSlot) }
        ScanPesaAccessibilityService.instance?.refreshWatched()
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }.getOrNull()
        val err = if (text == null) "Could not read the file" else FlowRepository.importOverride(ctx, text)
        hasOverride = FlowRepository.hasOverride(ctx)
        refreshApps()
        Toast.makeText(ctx, err ?: "Flows imported", Toast.LENGTH_LONG).show()
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(FlowRepository.bundledJson(ctx).toByteArray()) }
        }.onSuccess { Toast.makeText(ctx, "Saved built-in flows.json", Toast.LENGTH_SHORT).show() }
    }

    picking?.let { t ->
        AppPickerDialog(t, onPick = { pkg ->
            prefs.setPackageOverride(t, pkg); picking = null; refreshApps()
        }, onDismiss = { picking = null })
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("All saved scans will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false; vm.clearHistory()
                    Toast.makeText(ctx, "History cleared", Toast.LENGTH_SHORT).show()
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Advanced", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = Space.screen, end = Space.screen, bottom = Space.l),
        ) {
            if (Target.available.size > 1) {
                SectionLabel("Default app", Modifier.padding(top = 0.dp))
                RowGroup {
                    SettingsRow("Last one I used", trailing = { RadioMark(defaultTarget == null) }, onClick = {
                        defaultTarget = null; prefs.defaultTarget = null
                    })
                    Target.available.forEach { t ->
                        RowDivider()
                        SettingsRow(t.label, t.subtitle, trailing = { RadioMark(defaultTarget == t) }, onClick = {
                            defaultTarget = t; prefs.defaultTarget = t
                        })
                    }
                }
            }

            SectionLabel("Apps used", if (Target.available.size > 1) Modifier else Modifier.padding(top = 0.dp))
            RowGroup {
                Target.available.forEachIndexed { i, t ->
                    if (i > 0) RowDivider()
                    val custom = prefs.packageOverride(t) != null
                    SettingsRow(
                        t.label,
                        subtitle = (resolved[t] ?: "Not found on this phone") + if (custom) " · chosen by you" else "",
                        onClick = { picking = t },
                    )
                }
            }

            SectionLabel("Menu flows")
            RowGroup {
                SettingsRow(
                    "Flows in use",
                    subtitle = if (hasOverride) "Your imported flows.json" else "Built-in flows",
                )
                RowDivider()
                SettingsRow("Save built-in flows.json", subtitle = "Copy to edit menu labels", onClick = { exporter.launch("flows.json") })
                RowDivider()
                SettingsRow("Import flows.json", subtitle = "Use your edited copy", onClick = {
                    importer.launch(arrayOf("application/json", "text/plain", "*/*"))
                })
                if (hasOverride) {
                    RowDivider()
                    SettingsRow("Reset to built-in flows", onClick = { FlowRepository.resetOverride(ctx); hasOverride = false; refreshApps() })
                }
            }

            SectionLabel("Data")
            RowGroup {
                SettingsRow(
                    "Clear history",
                    subtitle = "Remove all saved scans",
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { confirmClear = true },
                    trailing = null,
                )
            }

            SectionLabel("Debugging")
            RowGroup {
                SwitchRow(
                    "Debug mode",
                    subtitle = "Log SIM Toolkit's screens (PIN fields are never included)",
                    checked = debug,
                ) { debug = it; prefs.debugMode = it }
                RowDivider()
                SettingsRow("Debug log", subtitle = "Automation steps and screen dumps", onClick = onDebug)
            }
        }
    }
}

@Composable
private fun RadioMark(selected: Boolean) =
    androidx.compose.material3.RadioButton(selected = selected, onClick = null)

@Composable
private fun AppPickerDialog(target: Target, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val apps = remember { TargetLauncher.launchableApps(ctx) }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App for ${target.label}") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("Search apps") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    item {
                        Text(
                            "Automatic (from flows.json)",
                            Modifier.fillMaxWidth().clickable { onPick(null) }.padding(vertical = 14.dp),
                            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(apps.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }) { a ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(a.packageName) }.padding(vertical = 10.dp)) {
                            Column {
                                Text(a.label, style = MaterialTheme.typography.titleMedium)
                                Text(a.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
