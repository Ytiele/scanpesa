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
    val preferredTarget: Target get() = defaultTarget ?: lastTarget ?: Target.MPESA_APP

    /** 1 or 2: which SIM's toolkit to open. */
    var simSlot: Int
        get() = sp.getInt("sim_slot", 1)
        set(v) = sp.edit().putInt("sim_slot", v.coerceIn(1, 2)).apply()

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
}
