package com.scanpesa.app.automation

/** Turns a screen into readable lines for Debug mode. Password nodes are excluded entirely. */
object NodeDumper {
    fun dump(root: UiNode): List<String> = Screen.of(root).nodes.map { f ->
        val n = f.node
        buildString {
            append("  ".repeat(f.depth.coerceAtMost(20)))
            append(n.className?.substringAfterLast('.') ?: "?")
            n.text?.takeIf { it.isNotBlank() }?.let { append(" text=\"").append(it.oneLine()).append('"') }
            n.contentDescription?.takeIf { it.isNotBlank() }?.let { append(" desc=\"").append(it.oneLine()).append('"') }
            n.hint?.takeIf { it.isNotBlank() }?.let { append(" hint=\"").append(it.oneLine()).append('"') }
            n.viewId?.takeIf { it.isNotBlank() }?.let { append(" id=").append(it) }
            val flags = listOfNotNull(
                "click".takeIf { n.isClickable }, "edit".takeIf { n.isEditable }, "scroll".takeIf { n.isScrollable },
            )
            if (flags.isNotEmpty()) append(" {").append(flags.joinToString(",")).append('}')
        }
    }

    private fun String.oneLine() = replace('\n', ' ').take(120)
}
