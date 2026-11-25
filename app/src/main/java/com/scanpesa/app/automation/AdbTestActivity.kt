package com.scanpesa.app.automation

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.scanpesa.app.data.Prefs
import com.scanpesa.app.model.PaymentType
import com.scanpesa.app.model.Target
import com.scanpesa.app.parser.PaymentValidator

/**
 * Starts an auto-fill job from a computer over USB, for tuning flows without tapping the phone:
 *
 *   adb shell am start -n com.scanpesa.app/.automation.AdbTestActivity \
 *       --es type SEND_MONEY --es number 0712345678 [--es account ABC]
 *
 * Protected by android.permission.DUMP, which only the adb shell and the system hold, so other
 * apps can't start jobs. It runs exactly like tapping Go (and still stops before amount/PIN).
 */
class AdbTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val msg = start()
        DebugLog.event("adb test: $msg")
        Toast.makeText(this, "ScanPesa test: $msg", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun start(): String {
        val type = PaymentType.fromName(intent.getStringExtra("type")) ?: return "bad or missing --es type"
        val rawNumber = intent.getStringExtra("number") ?: return "missing --es number"
        val account = intent.getStringExtra("account")?.let(PaymentValidator::cleanAccount)
        PaymentValidator.validate(type, rawNumber, account)?.let { return it }
        val target = Target.SIM_TOOLKIT
        val pkg = TargetLauncher.resolvePackage(this, target, Prefs(this).simSlot) ?: return "SIM Toolkit not found"
        val svc = ScanPesaAccessibilityService.instance ?: return "accessibility service not running"
        val now = System.currentTimeMillis()
        JobStore.start(Job(now, type, PaymentValidator.cleanNumber(type, rawNumber), account, target, pkg, now))
        if (!TargetLauncher.launch(this, target, pkg)) { JobStore.clear(); return "could not open $pkg" }
        svc.onJobStarted()
        return "started ${type.name} → $pkg"
    }
}
