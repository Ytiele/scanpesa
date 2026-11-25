package com.scanpesa.app.automation

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.scanpesa.app.Notifier
import com.scanpesa.app.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Types the confirmed number/account into the M-PESA app, SIM Toolkit or My Safaricom, then
 * stops so the user enters the amount and PIN. See FlowEngine for the safety rules.
 * Watches ONLY the packages listed in flows.json.
 */
class ScanPesaAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var watched: Set<String> = emptySet()

    private var engine: FlowEngine? = null
    private var engineJobId: Long = -1
    private var lastDumpAt = 0L
    private var lastDumpSignature = 0

    private val tickRunnable = Runnable { tick() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        instance = this
        _connected.value = true
        val cfg = runCatching { FlowRepository.get(this) }.getOrNull()
        watched = cfg?.allPackages ?: DEFAULT_PACKAGES
        serviceInfo = serviceInfo.apply { packageNames = watched.toTypedArray() }
        DebugLog.event("Service connected; watching ${watched.joinToString()}")
        if (JobStore.current != null) scheduleTick(300)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in watched) return
        if (prefs.debugMode) maybeDump(pkg, event)
        if (JobStore.current != null) scheduleTick(250)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        _connected.value = false
        super.onDestroy()
    }

    /** Called by the app right after it launches the target app. */
    fun onJobStarted() = scheduleTick(600)

    private fun scheduleTick(delay: Long) {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, delay)
    }

    private fun rootFor(pkg: String): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { if (it.packageName?.toString() == pkg) return it }
        return runCatching {
            windows.mapNotNull { it.root }.firstOrNull { it.packageName?.toString() == pkg }
        }.getOrNull()
    }

    private fun tick() {
        val job = JobStore.current ?: run { engine = null; return }
        val now = System.currentTimeMillis()
        val cfg = FlowRepository.get(this)
        if (engine == null || engineJobId != job.id) {
            val steps = cfg.targets[job.target]?.flows?.get(job.type)
            if (steps.isNullOrEmpty()) {
                finish(job, Outcome.Failed("setup", "No flow for ${job.type.label} in ${job.target.label} (flows.json)"))
                return
            }
            engine = FlowEngine(
                steps, JobValues(job.number, job.account), cfg, job.createdAt,
                placeholders = mapOf("SIM" to prefs.simSlot.toString()),
            )
            engineJobId = job.id
            DebugLog.event("Job ${job.type.label} → ${job.target.label} (${job.packageName}) started")
        }
        val e = engine!!
        val before = e.currentStepName
        val outcome = try {
            e.tick(rootFor(job.packageName)?.let(::AndroidNode), now)
        } catch (t: Throwable) {
            Outcome.Failed(e.currentStepName, "Internal error: ${t.javaClass.simpleName}")
        }
        if (e.currentStepName != before) DebugLog.event("✓ $before")
        when (outcome) {
            Outcome.Working -> scheduleTick(400) // keep polling until done/failed/timeout
            else -> finish(job, outcome)
        }
    }

    private fun finish(job: Job, outcome: Outcome) {
        JobStore.clear(job.id)
        engine = null
        handler.removeCallbacks(tickRunnable)
        when (outcome) {
            is Outcome.Done -> {
                DebugLog.event("DONE: ${outcome.message}")
                toast(outcome.message, long = true)
                Notifier.show(this, outcome.message, "ScanPesa entered ${describe(job)}. Now type the amount and your PIN yourself.")
            }
            is Outcome.Failed -> {
                DebugLog.event("FAILED at \"${outcome.step}\": ${outcome.reason}")
                toast("ScanPesa stopped at \"${outcome.step}\"", long = true)
                Notifier.show(
                    this, "Auto-fill stopped: ${outcome.step}",
                    "${outcome.reason}. Please continue by hand. Number: ${describe(job)}",
                )
            }
            Outcome.Working -> {}
        }
    }

    private fun describe(job: Job) = buildString {
        append(job.number)
        if (!job.account.isNullOrBlank()) append(", account ").append(job.account)
    }

    private fun toast(msg: String, long: Boolean) =
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ debug dumps

    private fun maybeDump(pkg: String, event: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        val stateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!stateChange && now - lastDumpAt < 1500) return
        val root = rootFor(pkg) ?: return
        val lines = NodeDumper.dump(AndroidNode(root))
        val sig = lines.hashCode()
        if (sig == lastDumpSignature) return
        lastDumpSignature = sig
        lastDumpAt = now
        val type = AccessibilityEvent.eventTypeToString(event.eventType)
        Log.d(DUMP_TAG, "===== $pkg ($type) ${lines.size} nodes =====")
        lines.forEach { Log.d(DUMP_TAG, it) }
        DebugLog.addDump(DumpEntry(System.currentTimeMillis(), pkg, type, lines))
    }

    companion object {
        const val DUMP_TAG = "ScanPesaDump"
        val DEFAULT_PACKAGES = setOf(
            "com.safaricom.mpesa.lifestyle", "com.safaricom.mysafaricom", "com.android.stk", "com.android.stk2",
        )

        @Volatile var instance: ScanPesaAccessibilityService? = null
            private set

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected
    }
}
