package com.scanpesa.app.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class DumpEntry(val time: Long, val packageName: String, val event: String, val lines: List<String>)

/** Recent node-tree dumps (Debug mode) and automation events, shown on the Debug screen. */
object DebugLog {
    private const val MAX_DUMPS = 15
    private const val MAX_EVENTS = 100

    private val _dumps = MutableStateFlow<List<DumpEntry>>(emptyList())
    val dumps: StateFlow<List<DumpEntry>> = _dumps

    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events

    fun addDump(entry: DumpEntry) = _dumps.update { (listOf(entry) + it).take(MAX_DUMPS) }

    fun event(msg: String) = _events.update { (listOf("${timeStr(System.currentTimeMillis())}  $msg") + it).take(MAX_EVENTS) }

    fun clear() { _dumps.value = emptyList(); _events.value = emptyList() }

    fun timeStr(t: Long): String = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(t))
}
