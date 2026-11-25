package com.scanpesa.app.automation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import com.scanpesa.app.data.Prefs
import com.scanpesa.app.model.Target

object TargetLauncher {

    /** First installed package for [target] (SIM slot matters for SIM Toolkit), or null. */
    fun resolvePackage(context: Context, target: Target, simSlot: Int): String? {
        Prefs(context).packageOverride(target)?.let { if (isInstalled(context, it)) return it }
        val tc = FlowRepository.get(context).targets[target] ?: return null
        val list = if (target == Target.SIM_TOOLKIT && simSlot == 2) tc.packagesSim2 else tc.packages
        return list.firstOrNull { isInstalled(context, it) }
    }

    /** Every package that may show [target]'s screens (flows.json + the Settings override). */
    fun packagesFor(context: Context, target: Target): Set<String> {
        val tc = FlowRepository.get(context).targets[target]
        return buildSet {
            tc?.let { addAll(it.packages); addAll(it.packagesSim2) }
            Prefs(context).packageOverride(target)?.let { add(it) }
        }
    }

    data class AppInfo(val label: String, val packageName: String)

    /** Launchable apps for the "Apps used" picker, likely matches first. */
    fun launchableApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .sortedWith(compareBy({ !looksRelevant(it) }, { it.label.lowercase() }))
    }

    private fun looksRelevant(a: AppInfo): Boolean {
        val s = (a.label + " " + a.packageName).lowercase()
        return listOf("safaricom", "m-pesa", "mpesa", "stk", "sim tool", "one app").any { it in s }
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
