package com.scanpesa.app.automation

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.scanpesa.app.Notifier
import com.scanpesa.app.data.Prefs
import com.scanpesa.app.model.Target
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Types the confirmed number/account into the M-PESA app, SIM Toolkit or One App, then
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
    private var jobPackages: Set<String> = emptySet()
    private var unlockHintShown = false
    private var overlay: StatusOverlay? = null
    private var eyes: Eyes? = null
    private var lastEyesLog = 0L
    private var lastTrace = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        overlay = StatusOverlay(this) { cancelJob() }
        eyes = Eyes(this) { overlay?.bounds() }
        instance = this
        _connected.value = true
        refreshWatched()
        if (JobStore.current != null) scheduleTick(300)
    }

    /** Re-reads flows.json + Settings overrides and watches exactly those packages. */
    fun refreshWatched() {
        val cfg = runCatching { FlowRepository.get(this) }.getOrNull()
        // Only the targets shown in the app (currently SIM Toolkit), plus any app picked in Settings.
        watched = if (cfg == null) DEFAULT_PACKAGES
        else Target.available.flatMap { TargetLauncher.packagesFor(this, it) }.toSet()
        serviceInfo = serviceInfo.apply { packageNames = watched.toTypedArray() }
        DebugLog.event("Watching ${watched.joinToString()}")
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
        overlay?.hide()
        eyes?.close()
        if (instance === this) instance = null
        _connected.value = false
        super.onDestroy()
    }

    /** Called by the app right after it launches the target app. */
    fun onJobStarted() {
        overlay?.show("Starting… opening ${JobStore.current?.target?.label ?: "app"}")
        scheduleTick(600)
    }

    /** The user came back to ScanPesa while a job was pending: drop it so nothing runs later. */
    fun cancelPending(reason: String) {
        val job = JobStore.current ?: return
        finish(job, Outcome.Failed(engine?.currentStepName ?: "start", reason))
    }

    /** Stop button on the status strip. */
    private fun cancelJob() {
        val job = JobStore.current ?: run { overlay?.hide(); return }
        finish(job, Outcome.Failed(engine?.currentStepName ?: "start", "Stopped by you"))
    }

    private fun scheduleTick(delay: Long) {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, delay)
    }

    private fun rootFor(pkg: String): AccessibilityNodeInfo? = rootFor(setOf(pkg))

    /** Root of the active window if it belongs to one of [pkgs], else of any window that does. */
    private fun rootFor(pkgs: Set<String>): AccessibilityNodeInfo? {
        // Drop the node cache first: Samsung's SIM Toolkit leaves it stale (empty menu list).
        if (Build.VERSION.SDK_INT >= 33) runCatching { clearCache() }
        rootInActiveWindow?.let { if (it.packageName?.toString() in pkgs) { it.refresh(); return it } }
        return runCatching {
            windows.mapNotNull { it.root }.firstOrNull { it.packageName?.toString() in pkgs }
        }.getOrNull()
    }

    private fun activePackage(): String? = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

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
            val loginWait = (cfg.targets[job.target]?.loginWaitSeconds ?: 0) * 1000L
            engine = FlowEngine(
                steps, JobValues(job.number, job.account), cfg, job.createdAt,
                placeholders = mapOf("SIM" to prefs.simSlot.toString()),
                loginWaitMs = loginWait,
                initialSettleMs = 1500, // Samsung & co. animate the window in; lists report hidden meanwhile
            )
            engineJobId = job.id
            jobPackages = TargetLauncher.packagesFor(this, job.target) + job.packageName
            unlockHintShown = loginWait == 0L
            DebugLog.event("Job ${job.type.label} → ${job.target.label} (${job.packageName}) started")
        }
        val e = engine!!
        val before = e.currentStepName
        val outcome = try {
            val root = rootFor(jobPackages)
            val why = if (root == null) {
                val seen = activePackage()
                "ScanPesa could not see ${job.target.label} on screen" +
                    (if (seen != null) " (it saw $seen; pick the right app in Settings > Apps used)" else "")
            } else null
            if (!unlockHintShown && !e.hasActed && now - job.createdAt > 4000) {
                unlockHintShown = true
                toast("Unlock ${job.target.label} if asked. ScanPesa will continue.", long = false)
            }
            overlay?.show(e.progress)
            val trace = e.progress + if (root == null) " | no root: $why" else " | root=${root.packageName} nodes=${root.childCount}"
            if (trace != lastTrace) { DebugLog.event(trace); lastTrace = trace }
            e.tick(root?.let { AndroidNode(it, this) { overlay?.passThrough() } }, now, why, sight(e, now, root != null))
        } catch (t: Throwable) {
            Outcome.Failed(e.currentStepName, "Internal error: ${t.javaClass.simpleName}")
        }
        if (e.currentStepName != before) DebugLog.event("✓ $before (${e.lastHow})")
        when (outcome) {
            Outcome.Working -> scheduleTick(400) // keep polling until done/failed/timeout
            else -> finish(job, outcome)
        }
    }

    /**
     * Extra senses for this tick. The eyes (screenshot + OCR) switch on once a step has waited
     * 1.2 s without the accessibility tree giving an answer; only screenshots taken after the
     * last action (+ settle time) are used.
     */
    private fun sight(e: FlowEngine, now: Long, haveRoot: Boolean): Sight {
        val ey = eyes
        if (haveRoot && ey != null && ey.supported && e.stepAge(now) > 1200) ey.look()
        val fresh = ey != null && ey.takenAt > e.lastActionAt + 800
        val lines = if (fresh) ey!!.lines else emptyList()
        if (fresh && ey!!.takenAt != lastEyesLog) {
            lastEyesLog = ey.takenAt
            DebugLog.event("eyes read: " + lines.joinToString(" | ") { it.text }.take(300))
        }
        ey?.lastError?.let { if (it != lastTrace) { DebugLog.event("eyes: $it"); lastTrace = it } }
        val focused = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?.takeIf { it.packageName?.toString() in jobPackages && !it.isPassword }
            ?.let { AndroidNode(it, this) }
        return Sight(
            lines = lines,
            focusedInput = focused,
            tapAt = { x, y -> tapAt(x, y) },
            screenHeight = resources.displayMetrics.heightPixels,
            goBack = { performGlobalAction(GLOBAL_ACTION_BACK).also { DebugLog.event("  pressed Back") } },
        )
    }

    /** Taps a screen position like a finger. Logs whether Android completed or cancelled it. */
    fun tapAt(x: Int, y: Int): Boolean {
        // Only touch the status strip's window if the tap is under it: changing a window right
        // before a gesture makes Android cancel the gesture.
        if (overlay?.bounds()?.contains(x, y) == true) overlay?.passThrough(700)
        val path = android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val g = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: android.accessibilityservice.GestureDescription?) { DebugLog.event("  tap completed at $x,$y") }
            override fun onCancelled(d: android.accessibilityservice.GestureDescription?) { DebugLog.event("  tap CANCELLED at $x,$y") }
        }, handler)
        DebugLog.event("  tap ${if (ok) "sent" else "REFUSED"} at $x,$y")
        eyes?.forget()
        return ok
    }

    private fun finish(job: Job, outcome: Outcome) {
        JobStore.clear(job.id)
        eyes?.forget()
        engine = null
        handler.removeCallbacks(tickRunnable)
        when (outcome) {
            is Outcome.Done -> {
                prefs.lastResult = Prefs.LastResult(true, outcome.message, "${job.target.label}: entered ${describe(job)}", System.currentTimeMillis())
                DebugLog.event("DONE: ${outcome.message}")
                toast(outcome.message, long = true)
                overlay?.show("Done ✓ ${outcome.message}", finished = true, autoHideMs = 6000)
                Notifier.show(this, outcome.message, "ScanPesa entered ${describe(job)}. Now type the amount and your PIN yourself.")
            }
            is Outcome.Failed -> {
                runCatching {
                    rootFor(jobPackages)?.let { r ->
                        DebugLog.event("Screen when it stopped:")
                        NodeDumper.dump(AndroidNode(r)).take(40).forEach { DebugLog.event("  $it") }
                    } ?: DebugLog.event("Screen when it stopped: not readable (active: ${activePackage()})")
                }
                prefs.lastResult = Prefs.LastResult(
                    false, "Stopped at \"${outcome.step}\" (${job.target.label})", outcome.reason, System.currentTimeMillis(),
                )
                DebugLog.event("FAILED at \"${outcome.step}\": ${outcome.reason}")
                toast("ScanPesa stopped at \"${outcome.step}\"", long = true)
                overlay?.show("Stopped at \"${outcome.step}\": ${outcome.reason}", error = true, finished = true, autoHideMs = 15000)
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
