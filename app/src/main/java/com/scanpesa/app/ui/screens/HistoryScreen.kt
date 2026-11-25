package com.scanpesa.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.scanpesa.app.R
import com.scanpesa.app.data.ScanEntity
import com.scanpesa.app.model.PaymentType
import com.scanpesa.app.ui.AppViewModel
import com.scanpesa.app.ui.Chevron
import com.scanpesa.app.ui.RowDivider
import com.scanpesa.app.ui.ScreenTopBar
import com.scanpesa.app.ui.SectionLabel
import com.scanpesa.app.ui.Space
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** History tab: last 50 scans grouped by day; tap one to pay it again. */
@Composable
fun HistoryScreen(vm: AppViewModel, onPick: (ScanEntity) -> Unit) {
    val items by vm.history.collectAsState()
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("History", null, subtitle = if (items.isEmpty()) null else "Tap a payment to use it again")
        if (items.isEmpty()) {
            EmptyHistory()
            return
        }
        val groups = items.groupBy { dayLabel(it.createdAt) }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = Space.l),
        ) {
            groups.forEach { (day, list) ->
                item(key = "h_$day") { SectionLabel(day) }
                item(key = "g_$day") {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            list.forEachIndexed { i, e ->
                                if (i > 0) RowDivider()
                                HistoryRow(e) { onPick(e) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(e: ScanEntity, onClick: () -> Unit) {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(e.createdAt))
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Space.m, vertical = Space.s + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                PaymentType.fromName(e.type)?.label ?: e.type,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(e.number, style = MaterialTheme.typography.titleLarge)
            e.account?.let {
                Text("Account $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(time, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Chevron()
    }
}

@Composable
private fun EmptyHistory() {
    Column(
        Modifier.fillMaxSize().padding(Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(R.drawable.ic_history), null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(Space.m))
        Text("No scans yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(Space.xs))
        Text(
            "Payments you scan appear here, so you can pay them again with one tap.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun dayLabel(t: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val today = Calendar.getInstance()
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (same(c, today)) return "Today"
    if (same(c, (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) })) return "Yesterday"
    return SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(t))
}
