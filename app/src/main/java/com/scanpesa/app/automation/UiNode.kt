package com.scanpesa.app.automation

/**
 * Minimal view of an accessibility node. The Android implementation wraps AccessibilityNodeInfo;
 * tests use plain fakes. Implementations MUST return null for all text of password nodes.
 */
interface UiNode {
    val text: String?
    val contentDescription: String?
    val hint: String?
    val viewId: String?
    val className: String?
    val isPassword: Boolean
    val isEditable: Boolean
    val isClickable: Boolean
    val isScrollable: Boolean
    val isVisible: Boolean
    val children: List<UiNode>

    fun click(): Boolean
    fun setText(value: String): Boolean
    fun scrollForward(): Boolean

    /** Fresh, app-side search for nodes containing [text] (case-insensitive). */
    fun findByText(text: String): List<UiNode> = emptyList()
}

/**
 * A non-password node plus its nearest clickable self/ancestor.
 * [visible] is false when the node (or an ancestor) reports not visible to the user; such nodes
 * are kept (Samsung reports whole lists as invisible during window animations) but ranked lower.
 */
class FlatNode(val node: UiNode, val depth: Int, val clickTarget: UiNode?, val order: Int, val visible: Boolean = true)

/** Snapshot of one screen. Password nodes and their subtrees are never included. */
class Screen(val nodes: List<FlatNode>, val passwordSeen: Boolean) {
    companion object {
        fun of(root: UiNode): Screen {
            val out = ArrayList<FlatNode>()
            var password = false
            fun walk(n: UiNode, depth: Int, clickable: UiNode?, parentVisible: Boolean) {
                if (n.isPassword) { password = true; return } // never read or descend into PIN fields
                val visible = parentVisible && n.isVisible
                val target = if (n.isClickable) n else clickable
                out += FlatNode(n, depth, target, out.size, visible)
                if (depth < 60) n.children.forEach { walk(it, depth + 1, target, visible) }
            }
            walk(root, 0, null, true)
            return Screen(out, password)
        }
    }
}

object Labels {
    private val leadingIndex = Regex("""^\s*\d{1,2}\s*[.):\-]\s*""")
    private val punct = Regex("""[^\p{L}\p{N}&/ ]""")

    /** "1. Send Money " -> "send money"; "Pay Bill..." -> "pay bill". */
    fun norm(s: String?): String =
        if (s.isNullOrBlank()) "" else punct.replace(leadingIndex.replace(s.lowercase(), ""), " ")
            .replace(Regex("\\s+"), " ").trim()

    /** 3 = exact, 2 = starts with, 1 = whole-word contains, 0 = no match. */
    fun score(label: String?, pattern: String): Int {
        val l = norm(label); val p = norm(pattern)
        if (l.isEmpty() || p.isEmpty()) return 0
        return when {
            l == p -> 3
            l.startsWith("$p ") -> 2
            Regex("""(^| )${Regex.escape(p)}( |$)""").containsMatchIn(l) -> 1
            else -> 0
        }
    }

    fun best(label: String?, patterns: List<String>): Int = patterns.maxOfOrNull { score(label, it) } ?: 0

    /** Own description of a field: hint, text, content description and the readable part of its view id. */
    fun fieldLabel(n: UiNode): String = listOfNotNull(
        n.hint, n.text, n.contentDescription,
        n.viewId?.substringAfter('/')?.replace('_', ' ')?.replace(Regex("([a-z])([A-Z])"), "$1 $2"),
    ).joinToString(" ")
}
