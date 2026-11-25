package com.scanpesa.app.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small strip at the top of the screen while ScanPesa drives SIM Toolkit, so the user can see
 * what it's doing (and where it stopped). Uses TYPE_ACCESSIBILITY_OVERLAY: no extra permission.
 */
class StatusOverlay(private val service: AccessibilityService, private val onStop: () -> Unit) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val ptHandler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var text: TextView? = null
    private var stop: Button? = null
    private var shown = ""

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), service.resources.displayMetrics).toInt()

    private fun ensure() {
        if (root != null) return
        val tv = TextView(service).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btn = Button(service).apply {
            text = "Stop"
            setOnClickListener { onStop() }
        }
        val bar = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(0xEE007A3B.toInt()); cornerRadius = dp(14).toFloat() }
            addView(tv); addView(btn)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            x = 0; y = dp(36)
            horizontalMargin = 0.03f
        }
        runCatching { wm.addView(bar, lp) }.onFailure { return }
        root = bar; text = tv; stop = btn
    }

    /** Shows a message; [autoHideMs] > 0 hides it after that long. */
    fun show(message: String, error: Boolean = false, finished: Boolean = false, autoHideMs: Long = 0) {
        handler.removeCallbacksAndMessages(null)
        ensure()
        if (message != shown) { text?.text = "ScanPesa · $message"; shown = message }
        (root?.background as? GradientDrawable)?.setColor(if (error) 0xEEB3261E.toInt() else 0xEE007A3B.toInt())
        stop?.text = if (finished) "OK" else "Stop"
        stop?.setOnClickListener { if (finished) hide() else onStop() }
        root?.visibility = View.VISIBLE
        if (autoHideMs > 0) handler.postDelayed({ hide() }, autoHideMs)
    }

    /** Lets touches fall through the strip for [ms] (so a simulated tap underneath isn't eaten). */
    fun passThrough(ms: Long = 500) {
        val v = root ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(v, lp) }
        ptHandler.removeCallbacksAndMessages(null)
        ptHandler.postDelayed({
            val r = root ?: return@postDelayed
            val l = r.layoutParams as? WindowManager.LayoutParams ?: return@postDelayed
            l.flags = l.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            runCatching { wm.updateViewLayout(r, l) }
        }, ms)
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        root?.let { runCatching { wm.removeView(it) } }
        root = null; text = null; stop = null; shown = ""
    }
}
