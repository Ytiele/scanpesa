package com.scanpesa.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scanpesa.app.automation.TargetLauncher
import com.scanpesa.app.model.Candidate
import com.scanpesa.app.model.PaymentType
import com.scanpesa.app.model.Target
import com.scanpesa.app.ui.AppViewModel
import com.scanpesa.app.ui.BigButton
import com.scanpesa.app.ui.BigOutlinedButton
import com.scanpesa.app.ui.Draft
import com.scanpesa.app.ui.GoResult
import com.scanpesa.app.ui.GreenLight
import com.scanpesa.app.ui.RadioRow
import com.scanpesa.app.ui.ScreenTopBar
import com.scanpesa.app.ui.SectionCard

@Composable
fun ConfirmScreen(vm: AppViewModel, onBack: () -> Unit, onRescan: () -> Unit) {
    val draft by vm.draft.collectAsState()
    val candidates by vm.candidates.collectAsState()
    val frame by vm.frozenFrame.collectAsState()

    Scaffold(topBar = { ScreenTopBar("Confirm", onBack) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            frame?.let {
                Image(
                    it.asImageBitmap(), contentDescription = "Scanned sign",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)),
                )
            }
            BigOutlinedButton("↻  Retake scan", onClick = onRescan)
            val d = draft
            when {
                d == null && candidates.size > 1 -> CandidatePicker(candidates, onPick = vm::pick, onRescan = onRescan)
                d == null -> {
                    Text("Nothing to confirm.")
                    BigButton("Scan again", onClick = onRescan)
                }
                !d.typeConfirmed -> PhoneTypeChooser(d.number) { type -> vm.draft.value = d.copy(type = type, typeConfirmed = true) }
                else -> ConfirmForm(vm, d, showBackToList = candidates.size > 1, onBackToList = { vm.draft.value = null })
            }
        }
    }
}

@Composable
private fun CandidatePicker(list: List<Candidate>, onPick: (Candidate) -> Unit, onRescan: () -> Unit) {
    Text("We found ${list.size} payment details. Which one?", style = MaterialTheme.typography.titleLarge)
    list.forEach { c ->
        SectionCard(Modifier.clickable { onPick(c) }) {
            Text(if (c.typeConfirmed) c.type.label else "Phone number", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(c.number, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            c.account?.let { Text("Account: $it", fontSize = 18.sp) }
        }
    }
    BigOutlinedButton("Scan again", onClick = onRescan)
}

@Composable
private fun PhoneTypeChooser(number: String, onChoose: (PaymentType) -> Unit) {
    SectionCard(container = GreenLight) {
        Text("We found a phone number", style = MaterialTheme.typography.titleLarge)
        Text(number, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
    Text("Send Money or Pochi la Biashara?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    BigButton("Send Money", onClick = { onChoose(PaymentType.SEND_MONEY) }, modifier = Modifier.heightIn(min = 84.dp))
    BigButton(
        "Pochi la Biashara", onClick = { onChoose(PaymentType.POCHI) },
        modifier = Modifier.heightIn(min = 84.dp), container = MaterialTheme.colorScheme.secondary,
    )
}

@Composable
private fun ConfirmForm(vm: AppViewModel, d: Draft, showBackToList: Boolean, onBackToList: () -> Unit) {
    val ctx = LocalContext.current
    var type by rememberSaveable(d) { mutableStateOf(d.type) }
    var number by rememberSaveable(d) { mutableStateOf(d.number) }
    var account by rememberSaveable(d) { mutableStateOf(d.account) }
    var target by rememberSaveable { mutableStateOf(vm.prefs.preferredTarget) }
    var error by remember { mutableStateOf<String?>(null) }
    var askService by remember { mutableStateOf<GoResult.NeedsService?>(null) }

    fun submit(autofill: Boolean) {
        when (val r = vm.go(type, number, account, target, autofill)) {
            GoResult.Launched -> error = null
            is GoResult.NeedsService -> askService = r
            is GoResult.Error -> error = r.message
        }
    }

    Text("Payment type", style = MaterialTheme.typography.titleMedium)
    val rows = listOf(listOf(PaymentType.SEND_MONEY, PaymentType.POCHI), listOf(PaymentType.PAYBILL, PaymentType.TILL))
    rows.forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { t ->
                FilterChip(
                    selected = type == t,
                    onClick = { type = t; error = null },
                    label = { Text(t.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }
    }

    val numberLabel = when (type) {
        PaymentType.SEND_MONEY, PaymentType.POCHI -> "Phone number"
        PaymentType.PAYBILL -> "Business (Paybill) number"
        PaymentType.TILL -> "Till number"
    }
    OutlinedTextField(
        value = number, onValueChange = { number = it; error = null },
        label = { Text(numberLabel) },
        textStyle = MaterialTheme.typography.headlineSmall,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (type == PaymentType.PAYBILL || type == PaymentType.TILL) KeyboardType.Number else KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )
    if (type == PaymentType.PAYBILL) {
        OutlinedTextField(
            value = account, onValueChange = { account = it; error = null },
            label = { Text("Account number") },
            placeholder = { Text("Required: type the account number") },
            isError = account.isBlank(),
            supportingText = { if (account.isBlank()) Text("No account number was found. Please enter it.") },
            textStyle = MaterialTheme.typography.headlineSmall,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    SectionCard {
        Text("Open in", style = MaterialTheme.typography.titleMedium)
        if (Target.available.size == 1) {
            Text("${target.label} · SIM ${vm.prefs.simSlot}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text("Change the SIM slot in Settings.", style = MaterialTheme.typography.bodyMedium)
        } else Target.available.forEach { t ->
            val subtitle = if (t == Target.SIM_TOOLKIT) "${t.subtitle} · SIM ${vm.prefs.simSlot}" else t.subtitle
            RadioRow(selected = target == t, title = t.label, subtitle = subtitle) { target = t; error = null }
        }
    }

    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
    BigButton("Go", onClick = { submit(autofill = true) }, modifier = Modifier.heightIn(min = 72.dp))
    Text(
        "ScanPesa will open ${target.label} and type the numbers. You enter the amount and PIN.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (showBackToList) BigOutlinedButton("Pick a different result", onClick = onBackToList)

    askService?.let { need ->
        AlertDialog(
            onDismissRequest = { askService = null },
            title = { Text(if (need.enabledButNotRunning) "Auto-fill is not running" else "Auto-fill is off") },
            text = {
                Text(
                    if (need.enabledButNotRunning)
                        "\"ScanPesa auto-fill\" is switched on, but your phone has stopped it (battery saver). " +
                            "In Accessibility settings switch it OFF and ON again. Also set ScanPesa's battery usage to \"Unrestricted\"."
                    else "Turn on \"ScanPesa auto-fill\" in Accessibility settings so ScanPesa can type the numbers. Or open ${target.label} and type them yourself.",
                )
            },
            confirmButton = {
                TextButton(onClick = { askService = null; TargetLauncher.openAccessibilitySettings(ctx) }) { Text("Fix in settings") }
            },
            dismissButton = {
                TextButton(onClick = { askService = null; submit(autofill = false) }) { Text("Just open the app") }
            },
        )
    }
}
