package com.scanpesa.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.scanpesa.app.R
import com.scanpesa.app.automation.TargetLauncher
import com.scanpesa.app.model.PaymentType
import com.scanpesa.app.parser.PhotoTokens
import com.scanpesa.app.ui.AppViewModel
import com.scanpesa.app.ui.BigButton
import com.scanpesa.app.ui.GoResult
import com.scanpesa.app.ui.Green
import com.scanpesa.app.ui.ScreenTopBar
import com.scanpesa.app.ui.Space
import com.scanpesa.app.ui.WarnLight
import kotlinx.coroutines.delay

/**
 * Read-only result of a scan: what ScanPesa decided to pay, highlighted on the photo.
 * The only actions are Retake and Go: nothing to select, type or edit.
 */
@Composable
fun ConfirmScreen(vm: AppViewModel, onBack: () -> Unit, onRescan: () -> Unit) {
    val ctx = LocalContext.current
    val d by vm.draft.collectAsState()
    val photo by vm.photo.collectAsState()
    val boxes by vm.highlight.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var askService by remember { mutableStateOf<GoResult.NeedsService?>(null) }
    val target = remember { vm.prefs.preferredTarget }

    // Go is locked briefly when the screen appears (a tap meant for the shutter can't hit Go),
    // and only starts one job per visit.
    var goArmed by remember(d) { mutableStateOf(false) }
    var launched by remember(d) { mutableStateOf(false) }
    LaunchedEffect(d, launched) { if (!launched) { goArmed = false; delay(1500); goArmed = true } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (launched) launched = false }

    val draft = d
    val missingAccount = draft != null && draft.type == PaymentType.PAYBILL && draft.account.isBlank()
    val canGo = draft != null && !missingAccount && goArmed && !launched

    fun submit(autofill: Boolean) {
        val p = draft ?: return
        if (!goArmed || launched) return
        when (val r = vm.go(p.type, p.number, p.account, target, autofill)) {
            GoResult.Launched -> { error = null; launched = true }
            is GoResult.NeedsService -> askService = r
            is GoResult.Error -> error = r.message
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScreenTopBar(if (draft == null) "Nothing found" else "Ready to pay", onBack)

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.screen),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            // The photo with the chosen number (and account) highlighted
            photo?.let { p ->
                Box(Modifier.fillMaxWidth().height(260.dp).clip(MaterialTheme.shapes.large).background(Color.Black)) {
                    Image(p.bitmap.asImageBitmap(), "Scanned photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize()) {
                        val pad = 4.dp.toPx()
                        boxes.forEach { b0 ->
                            val b = PhotoTokens.mapFit(b0, p.bitmap.width, p.bitmap.height, size.width, size.height)
                            val tl = Offset(b.left - pad, b.top - pad)
                            val sz = Size(b.right - b.left + 2 * pad, b.bottom - b.top + 2 * pad)
                            drawRoundRect(Green.copy(alpha = 0.28f), tl, sz, CornerRadius(8.dp.toPx()))
                            drawRoundRect(Green, tl, sz, CornerRadius(8.dp.toPx()), style = Stroke(3.dp.toPx()))
                        }
                    }
                }
            }

            if (draft == null) {
                Text(
                    "No Paybill, Till or phone number could be read. Move a little closer, make sure the numbers are sharp, and retake.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text(draft.type.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(draft.number, style = MaterialTheme.typography.displaySmall)
                        if (draft.type == PaymentType.PAYBILL) {
                            Spacer(Modifier.height(Space.xs))
                            Text("ACCOUNT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                draft.account.ifBlank { "Not found" },
                                style = MaterialTheme.typography.headlineSmall,
                                color = if (missingAccount) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                Text(
                    "Check this matches the sign. M-PESA shows the recipient's name before you enter your PIN.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (missingAccount) {
                    Surface(shape = MaterialTheme.shapes.medium, color = WarnLight, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "The account number isn't readable on this photo. Retake closer to the account number.",
                            style = MaterialTheme.typography.bodyMedium, color = Color(0xFF6B3A00),
                            modifier = Modifier.padding(Space.m),
                        )
                    }
                }
            }
            Spacer(Modifier.height(Space.xs))
        }

        // Pinned actions: Retake · Go
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = Space.screen, end = Space.screen, top = Space.s, bottom = Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (draft != null) {
                Text(
                    "Opens ${target.label} · SIM ${vm.prefs.simSlot}. You enter the amount and PIN.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onRescan,
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = Space.s),
                ) {
                    Icon(painterResource(R.drawable.ic_scan), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(Space.xs))
                    Text("Retake", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false)
                }
                if (draft != null) {
                    BigButton(
                        if (canGo || missingAccount) "Go" else "Go (wait…)",
                        onClick = { submit(autofill = true) },
                        enabled = canGo,
                        modifier = Modifier.weight(1.3f).heightIn(min = 64.dp),
                    )
                }
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
                    else "Turn on \"ScanPesa auto-fill\" in Accessibility settings so ScanPesa can fill in SIM Toolkit for you.",
                )
            },
            confirmButton = {
                TextButton(onClick = { askService = null; TargetLauncher.openAccessibilitySettings(ctx) }) { Text("Fix in settings") }
            },
            dismissButton = { TextButton(onClick = { askService = null }) { Text("Cancel") } },
        )
    }
}
