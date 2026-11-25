package com.scanpesa.app.automation

import android.content.Context
import java.io.File

/**
 * Loads flows from an imported override file (Settings -> Import flows.json) if present,
 * otherwise from the bundled assets/flows.json.
 */
object FlowRepository {
    private const val OVERRIDE = "flows_override.json"
    @Volatile private var cached: FlowConfig? = null

    fun get(context: Context): FlowConfig = cached ?: synchronized(this) {
        cached ?: load(context).also { cached = it }
    }

    private fun load(context: Context): FlowConfig {
        val override = File(context.filesDir, OVERRIDE)
        if (override.exists()) {
            runCatching { return FlowConfig.parse(override.readText()) }
            override.delete() // broken override: fall back to the built-in flows
        }
        return FlowConfig.parse(context.assets.open("flows.json").bufferedReader().use { it.readText() })
    }

    fun hasOverride(context: Context) = File(context.filesDir, OVERRIDE).exists()

    /** Validates and stores an imported flows file. Returns an error message or null on success. */
    fun importOverride(context: Context, json: String): String? {
        val cfg = try { FlowConfig.parse(json) } catch (e: IllegalArgumentException) { return e.message }
        File(context.filesDir, OVERRIDE).writeText(json)
        cached = cfg
        return null
    }

    fun resetOverride(context: Context) {
        File(context.filesDir, OVERRIDE).delete()
        cached = null
    }

    fun bundledJson(context: Context): String =
        context.assets.open("flows.json").bufferedReader().use { it.readText() }
}
