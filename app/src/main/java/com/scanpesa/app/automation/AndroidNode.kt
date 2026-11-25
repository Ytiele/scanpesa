package com.scanpesa.app.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * [UiNode] over AccessibilityNodeInfo. Password nodes expose no text and accept no actions.
 * [service] is used for fallbacks: a real tap gesture when ACTION_CLICK isn't supported, and
 * paste when ACTION_SET_TEXT is refused.
 */
class AndroidNode(
    private val info: AccessibilityNodeInfo,
    private val service: AccessibilityService? = null,
    private val beforeTap: (() -> Unit)? = null,
) : UiNode {
    override val isPassword: Boolean = info.isPassword

    override val text: String? get() = if (isPassword) null else info.text?.toString()
    override val contentDescription: String? get() = if (isPassword) null else info.contentDescription?.toString()
    override val hint: String? get() = if (isPassword) null else info.hintText?.toString()
    override val viewId: String? get() = info.viewIdResourceName
    override val className: String? get() = info.className?.toString()
    override val isEditable: Boolean get() = info.isEditable || className?.contains("EditText") == true
    override val isClickable: Boolean get() = info.isClickable
    override val isScrollable: Boolean get() = info.isScrollable
    override val isVisible: Boolean get() = info.isVisibleToUser

    override val children: List<UiNode>
        get() = if (isPassword) emptyList() else (0 until info.childCount).mapNotNull { info.getChild(it)?.let { c -> AndroidNode(c, service, beforeTap) } }

    override fun click(): Boolean {
        if (isPassword) return false
        if (info.isClickable && info.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            DebugLog.event("  click ok: ${label()}")
            return true
        }
        // Walk up: some list rows only accept the click on a parent.
        var p = info.parent
        var depth = 0
        while (p != null && depth < 4) {
            if (p.isPassword) break
            if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                DebugLog.event("  click ok via parent: ${label()}")
                return true
            }
            p = p.parent; depth++
        }
        return tap()
    }

    /** Taps the centre of the node like a finger would. */
    private fun tap(): Boolean {
        val svc = service ?: return false
        val r = Rect().also { info.getBoundsInScreen(it) }
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        beforeTap?.invoke()
        val ok = svc.dispatchGesture(gesture, null, null)
        DebugLog.event("  tap ${if (ok) "sent" else "FAILED"} at ${r.centerX()},${r.centerY()}: ${label()}")
        return ok
    }

    override fun setText(value: String): Boolean {
        if (isPassword) return false
        info.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        if (info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            DebugLog.event("  typed into field: ${label()}")
            return true
        }
        // Fallback: paste. Only the confirmed number/account ever goes on the clipboard.
        val svc = service ?: return false
        val cm = svc.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("ScanPesa", value))
        val ok = info.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        DebugLog.event("  paste ${if (ok) "ok" else "FAILED"} into field: ${label()}")
        return ok
    }

    override fun scrollForward(): Boolean = info.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

    private fun label(): String =
        (text ?: contentDescription ?: hint ?: viewId ?: className ?: "?").take(40)
}
