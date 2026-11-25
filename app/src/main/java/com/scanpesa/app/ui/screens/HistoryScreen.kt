package com.scanpesa.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scanpesa.app.data.ScanEntity
import com.scanpesa.app.model.PaymentType
import com.scanpesa.app.model.Target
import com.scanpesa.app.ui.AppViewModel
import com.scanpesa.app.ui.ScreenTopBar
import com.scanpesa.app.ui.SectionCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(vm: AppViewModel, onBack: () -> Unit, onPick: (ScanEntity) -> Unit) {
    val items by vm.history.collectAsState()
    val fmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
    Scaffold(topBar = { ScreenTopBar("History (last 50)", onBack) }) { pad ->
        if (items.isEmpty()) {
            Text("No scans yet.", Modifier.padding(pad).padding(20.dp), style = MaterialTheme.typography.bodyLarge)
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items, key = { it.id }) { e ->
                SectionCard(Modifier.clickable { onPick(e) }) {
                    Text(
                        PaymentType.fromName(e.type)?.label ?: e.type,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                    )
                    Text(e.number, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    e.account?.let { Text("Account: $it") }
                    Text(
                        "${fmt.format(Date(e.createdAt))} · ${Target.fromName(e.target)?.label ?: e.target} · tap to reuse",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
