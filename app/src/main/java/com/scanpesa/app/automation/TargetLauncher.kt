package com.scanpesa.app.automation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import com.scanpesa.app.model.Target

object TargetLauncher {

    /** First installed package for [target] (SIM slot matters for SIM Toolkit), or null. */
    fun resolvePackage(context: Context, target: Target, simSlot: Int): String? {
        val tc = FlowRepository.get(context).targets[target] ?: return null
        val list = if (target == Target.SIM_TOOLKIT && simSlot == 2) tc.packagesSim2 else tc.packages
        return list.firstOrNull { isInstalled(context, it) }
    }

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    /** Opens the app at its start screen. Returns false if it has no launchable activity. */
    fun launch(context: Context, target: Target, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        val clear = FlowRepository.get(context).targets[target]?.clearTask ?: true
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (clear) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return try { context.startActivity(intent); true } catch (_: Exception) { false }
    }

    /** Is the ScanPesa accessibility service switched on in system settings? */
    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, ScanPesaAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        return splitter.any { ComponentName.unflattenFromString(it) == expected }
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
