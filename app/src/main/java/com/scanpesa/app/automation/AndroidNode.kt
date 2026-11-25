package com.scanpesa.app.automation

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/** [UiNode] over AccessibilityNodeInfo. Password nodes expose no text and accept no actions. */
class AndroidNode(private val info: AccessibilityNodeInfo) : UiNode {
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
        get() = if (isPassword) emptyList() else (0 until info.childCount).mapNotNull { info.getChild(it)?.let(::AndroidNode) }

    override fun click(): Boolean = !isPassword && info.performAction(AccessibilityNodeInfo.ACTION_CLICK)

    override fun setText(value: String): Boolean {
        if (isPassword) return false
        info.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun scrollForward(): Boolean = info.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
}
