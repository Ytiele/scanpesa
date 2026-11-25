package com.scanpesa.app.data

import android.content.Context
import android.content.SharedPreferences
import com.scanpesa.app.model.Target

/** Small settings store. SharedPreferences so the accessibility service can read it synchronously. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("scanpesa_settings", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = sp.getBoolean("onboarding_done", false)
        set(v) = sp.edit().putBoolean("onboarding_done", v).apply()

    /** Default target from Settings; null = "use the last one I picked". */
    var defaultTarget: Target?
        get() = Target.fromName(sp.getString("default_target", null))
        set(v) = sp.edit().putString("default_target", v?.name).apply()

    var lastTarget: Target?
        get() = Target.fromName(sp.getString("last_target", null))
        set(v) = sp.edit().putString("last_target", v?.name).apply()

    /** Target pre-selected on the confirmation screen. */
    val preferredTarget: Target
        get() = (defaultTarget ?: lastTarget)?.takeIf { it.enabled } ?: Target.available.first()

    /** 1 or 2: which SIM's toolkit to open. */
    var simSlot: Int
        get() = sp.getInt("sim_slot", 1)
        set(v) = sp.edit().putInt("sim_slot", v.coerceIn(1, 2)).apply()

    /** Capture automatically once a reading is steady (off: the user presses the shutter). */
    var autoCapture: Boolean
        get() = sp.getBoolean("auto_capture", false)
        set(v) = sp.edit().putBoolean("auto_capture", v).apply()

    var vibrateOnDetect: Boolean
        get() = sp.getBoolean("vibrate", true)
        set(v) = sp.edit().putBoolean("vibrate", v).apply()

    /** Hidden until the version line in Settings is tapped 7 times. */
    var debugUnlocked: Boolean
        get() = sp.getBoolean("debug_unlocked", false)
        set(v) = sp.edit().putBoolean("debug_unlocked", v).apply()

    var debugMode: Boolean
        get() = sp.getBoolean("debug_mode", false)
        set(v) = sp.edit().putBoolean("debug_mode", v).apply()

    /** Package chosen in Settings > Apps used; overrides flows.json. Null = automatic. */
    fun packageOverride(target: Target): String? = sp.getString("pkg_${target.name}", null)

    fun setPackageOverride(target: Target, pkg: String?) = sp.edit().putString("pkg_${target.name}", pkg).apply()

    val allPackageOverrides: Set<String>
        get() = Target.entries.mapNotNull { packageOverride(it) }.toSet()

    /** Result of the last auto-fill job, shown on the home screen. */
    data class LastResult(val ok: Boolean, val title: String, val detail: String, val time: Long)

    var lastResult: LastResult?
        get() {
            val title = sp.getString("last_title", null) ?: return null
            return LastResult(sp.getBoolean("last_ok", false), title, sp.getString("last_detail", "").orEmpty(), sp.getLong("last_time", 0))
        }
        set(v) {
            sp.edit().apply {
                if (v == null) remove("last_title")
                else putBoolean("last_ok", v.ok).putString("last_title", v.title).putString("last_detail", v.detail).putLong("last_time", v.time)
            }.apply()
        }
}
