package com.scanpesa.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.scanpesa.app.automation.TargetLauncher
import com.scanpesa.app.model.Candidate
import com.scanpesa.app.model.PaymentType
import com.scanpesa.app.model.Target
import com.scanpesa.app.ui.AppViewModel
import com.scanpesa.app.ui.BigButton
import com.scanpesa.app.ui.BigOutlinedButton
import com.scanpesa.app.ui.Chevron
import com.scanpesa.app.ui.Draft
import com.scanpesa.app.ui.GoResult
import com.scanpesa.app.ui.GreenLight
import com.scanpesa.app.ui.RadioRow
import com.scanpesa.app.ui.RowDivider
import com.scanpesa.app.ui.RowGroup
import com.scanpesa.app.ui.ScreenTopBar
import com.scanpesa.app.ui.SectionCard
import com.scanpesa.app.ui.Space
import kotlinx.coroutines.delay

@Composable
fun ConfirmScreen(vm: AppViewModel, onBack: () -> Unit, onRescan: () -> Unit) {
    val draft by vm.draft.collectAsState()
    val candidates by vm.candidates.collectAsState()
    val frame by vm.frozenFrame.collectAsState()
    val d = draft

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScreenTopBar(
            when {
                d == null && candidates.size > 1 -> "Pick one"
                d != null && !d.typeConfirmed -> "Phone number"
                else -> "Confirm"
            },
            onBack,
        )
        val header: @Composable () -> Unit = {
            frame?.let {
                Image(
                    it.asImageBitmap(), contentDescription = "Scanned sign",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(132.dp).clip(MaterialTheme.shapes.large),
                )
            }
        }
        when {
            d != null && d.typeConfirmed ->
                ConfirmForm(vm, d, header, onRescan, showBackToList = candidates.size > 1, onBackToList = { vm.draft.value = null })
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(horizontal = Space.screen, vertical = Space.xs),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                header()
                when {
                    d == null && candidates.size > 1 -> CandidatePicker(candidates, onPick = vm::pick, onRescan = onRescan)
                    d == null -> {
                        Text("Nothing to confirm.", style = MaterialTheme.typography.bodyLarge)
                        BigButton("Scan again", onClick = onRescan)
                    }
                    else -> PhoneTypeChooser(d.number) { type -> vm.draft.value = d.copy(type = type, typeConfirmed = true) }
                }
            }
        }
    }
}

@Composable
private fun CandidatePicker(list: List<Candidate>, onPick: (Candidate) -> Unit, onRescan: () -> Unit) {
    Text(
        "We found ${list.size} payment details on this sign.",
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    RowGroup {
        list.forEachIndexed { i, c ->
            if (i > 0) RowDivider()
            Row(
                Modifier.fillMaxWidth().clickable { onPick(c) }.padding(horizontal = Space.m, vertical = Space.s + 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (c.typeConfirmed) c.type.label else "Phone number",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    )
                    Text(c.number, style = MaterialTheme.typography.headlineSmall)
                    c.account?.let { Text("Account $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Chevron()
            }
        }
    }
    BigOutlinedButton("Scan again", onClick = onRescan)
}

@Composable
private fun PhoneTypeChooser(number: String, onChoose: (PaymentType) -> Unit) {
    Spacer(Modifier.height(Space.s))
    Text(number, style = MaterialTheme.typography.displaySmall)
    Text(
        "Is this for Send Money or Pochi la Biashara?",
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Space.s))
    BigButton("Send Money", onClick = { onChoose(PaymentType.SEND_MONEY) }, modifier = Modifier.heightIn(min = 72.dp))
    BigButton(
        "Pochi la Biashara", onClick = { onChoose(PaymentType.POCHI) },
        modifier = Modifier.heightIn(min = 72.dp), container = MaterialTheme.colorScheme.secondary,
    )
}

private val PaymentType.short: String
    get() = when (this) {
        PaymentType.SEND_MONEY -> "Send"
        PaymentType.POCHI -> "Pochi"
        PaymentType.PAYBILL -> "Paybill"
        PaymentType.TILL -> "Till"
    }

@Composable
private fun ConfirmForm(
    vm: AppViewModel,
    d: Draft,
    header: @Composable () -> Unit,
    onRescan: () -> Unit,
    showBackToList: Boolean,
    onBackToList: () -> Unit,
) {
    val ctx = LocalContext.current
    var type by rememberSaveable(d) { mutableStateOf(d.type) }
    var number by rememberSaveable(d) { mutableStateOf(d.number) }
    var account by rememberSaveable(d) { mutableStateOf(d.account) }
    var target by rememberSaveable { mutableStateOf(vm.prefs.preferredTarget) }
    var error by remember { mutableStateOf<String?>(null) }
    var tried by remember(d) { mutableStateOf(false) }
    var askService by remember { mutableStateOf<GoResult.NeedsService?>(null) }

    // Go is locked for a moment whenever this form (re)appears, so a tap meant for the previous
    // screen ("Use this", "Send Money / Pochi") can never land on Go and open SIM Toolkit.
    var goArmed by remember(d) { mutableStateOf(false) }
    LaunchedEffect(d) {
        goArmed = false
        delay(1500)
        goArmed = true
    }
    // One job per tap: after launching, Go stays off until the user comes back and waits again.
    var launched by remember(d) { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (launched) {
            launched = false
            goArmed = false
        }
    }
    LaunchedEffect(launched, goArmed) {
        if (!launched && !goArmed) { delay(1500); goArmed = true }
    }

    fun submit(autofill: Boolean) {
        if (!goArmed || launched) return
        tried = true
        when (val r = vm.go(type, number, account, target, autofill)) {
            GoResult.Launched -> { error = null; launched = true }
            is GoResult.NeedsService -> askService = r
            is GoResult.Error -> error = r.message
        }
    }

    val fieldShape = MaterialTheme.shapes.medium
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.screen, vertical = Space.xs),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            header()

            // Payment type: one segmented control
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                PaymentType.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = type == t,
                        onClick = { type = t; error = null },
                        shape = SegmentedButtonDefaults.itemShape(i, PaymentType.entries.size),
                        icon = {},
                        modifier = Modifier.heightIn(min = 52.dp),
                    ) { Text(t.short, maxLines = 1, style = MaterialTheme.typography.bodyMedium) }
                }
            }

            val numberLabel = when (type) {
                PaymentType.SEND_MONEY, PaymentType.POCHI -> "Phone number"
                PaymentType.PAYBILL -> "Business number"
                PaymentType.TILL -> "Till number"
            }
            OutlinedTextField(
                value = number, onValueChange = { number = it; error = null },
                label = { Text(numberLabel) },
                textStyle = MaterialTheme.typography.headlineSmall,
                singleLine = true,
                shape = fieldShape,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (type == PaymentType.PAYBILL || type == PaymentType.TILL) KeyboardType.Number else KeyboardType.Phone,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (type == PaymentType.PAYBILL) {
                val missing = account.isBlank()
                OutlinedTextField(
                    value = account, onValueChange = { account = it; error = null },
                    label = { Text("Account number") },
                    isError = missing && tried,
                    supportingText = {
                        if (missing) Text(if (tried) "Enter the account number" else "Not found on the sign. Type it in.")
                    },
                    textStyle = MaterialTheme.typography.headlineSmall,
                    singleLine = true,
                    shape = fieldShape,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (Target.available.size > 1) {
                RowGroup {
                    Target.available.forEach { t ->
                        val subtitle = if (t == Target.SIM_TOOLKIT) "${t.subtitle} · SIM ${vm.prefs.simSlot}" else t.subtitle
                        RadioRow(selected = target == t, title = t.label, subtitle = subtitle) { target = t; error = null }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                TextButton(onClick = onRescan) { Text("Retake scan") }
                if (showBackToList) TextButton(onClick = onBackToList) { Text("Other result") }
            }
        }

        // Pinned action area
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                    .padding(start = Space.screen, end = Space.screen, top = Space.s, bottom = Space.m),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Text(
                    "Opens ${target.label} · SIM ${vm.prefs.simSlot}. You enter the amount and PIN.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                BigButton(
                    if (goArmed && !launched) "Go" else "Go (wait…)",
                    onClick = { submit(autofill = true) },
                    enabled = goArmed && !launched,
                    modifier = Modifier.heightIn(min = 64.dp),
                )
            }
        }
    }

    askService?.let { need ->
        AlertDialog(
            onDismissRequest = { askService = null },
            title = { Text(if (need.enabledButNotRunning) "Auto-fill is not running" else "Auto-fill is off") },
            text = {
                Text(
                    if (need.enabledButNotRunning)
                        "\"ScanPesa auto-fill\" is switched on, but your phone has stopped it (battery saver). " +
                            "In Accessibility settings switch it off and on again, and set ScanPesa's battery use to Unrestricted."
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
