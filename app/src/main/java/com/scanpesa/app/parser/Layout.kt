package com.scanpesa.app.parser

/**
 * Rebuilds reading order from word positions instead of trusting the OCR engine's line order.
 * Boxed digits ("[8][8][4][8][8][3][4]") often come back as separate lines or out of order; grouping
 * words that sit on the same visual row and reading them left to right puts them back together.
 */
object Layout {

    private fun cy(b: Box) = (b.top + b.bottom) / 2f
    private fun h(b: Box) = b.bottom - b.top

    /** Words grouped into visual rows (top to bottom), each row left to right. */
    fun rows(words: List<OcrWord>): List<OcrLine> {
        val rows = mutableListOf<MutableList<OcrWord>>()
        for (w in words.sortedBy { cy(it.box) }) {
            val row = rows.firstOrNull { r ->
                val rc = r.map { cy(it.box) }.average().toFloat()
                val rh = r.maxOf { h(it.box) }
                kotlin.math.abs(cy(w.box) - rc) <= 0.5f * maxOf(h(w.box), rh)
            }
            if (row != null) row += w else rows += mutableListOf(w)
        }
        return rows.sortedBy { r -> r.minOf { it.box.top } }.map { r -> OcrLine(r.sortedBy { it.box.left }) }
    }

    private fun digitPiece(t: String): Int? {
        val n = MpesaParser.normalize(t)
        if (n.isEmpty() || n.length > 4 || !n.all { it.isDigit() }) return null
        return n.length
    }

    /** Text of the rows. Short digit pieces next to each other (boxed digits, "0712 345 678") are joined. */
    fun text(rows: List<OcrLine>): String = rows.joinToString("\n") { row ->
        val sb = StringBuilder()
        row.words.forEachIndexed { i, w ->
            if (i > 0) {
                val prev = row.words[i - 1]
                val join = digitPiece(prev.text) != null && digitPiece(w.text) != null &&
                    (w.box.left - prev.box.right) < 2.5f * maxOf(h(prev.box), h(w.box))
                if (!join) sb.append(' ')
            }
            sb.append(w.text)
        }
        sb.toString()
    }
}

/**
 * Combines several readings of the same photo (different image versions / reading orders).
 * The payment most readings agree on wins; if readings disagree on the number, it's "not sure"
 * and the user is asked to retake instead of risking a wrong payment.
 */
object Consensus {

    data class Decision(val pick: PhotoTokens.AutoPick?, val uncertain: Boolean, val votes: Int, val readings: Int)

    fun decide(picks: List<PhotoTokens.AutoPick?>): Decision {
        val valid = picks.filterNotNull()
        if (valid.isEmpty()) return Decision(null, uncertain = false, votes = 0, readings = picks.size)
        val byNumber = valid.groupBy { it.candidate.number }.entries.sortedByDescending { it.value.size }
        val top = byNumber[0]
        val second = byNumber.getOrNull(1)?.value?.size ?: 0
        // Agreement needed: if any reading saw a different number, the winner needs 3x its votes
        // (and at least 3). Otherwise it's "not sure": a misread digit must never become a payment.
        if (second > 0 && (top.value.size < 3 * second || top.value.size < 3)) {
            return Decision(null, uncertain = true, votes = top.value.size, readings = picks.size)
        }
        val group = top.value
        // Type: majority within the group.
        val type = group.groupBy { it.candidate.type }.maxBy { it.value.size }.key
        val ofType = group.filter { it.candidate.type == type }
        // Account: most frequent non-empty (ignoring case/separators); tie -> longest.
        val norm = { s: String -> s.uppercase().filter { it.isLetterOrDigit() } }
        val accounts = ofType.mapNotNull { it.candidate.account?.takeIf { a -> a.isNotBlank() } }
        val accGroups = accounts.groupBy(norm).entries
            .sortedWith(compareByDescending<Map.Entry<String, List<String>>> { it.value.size }.thenByDescending { it.key.length })
        val accTop = accGroups.firstOrNull()?.value?.size ?: 0
        val accSecond = accGroups.getOrNull(1)?.value?.size ?: 0
        // Readings disagree on the account: treat it as unreadable (Go stays off, user retakes).
        val account = if (accSecond > 0 && accTop < 3 * accSecond) null else accGroups.firstOrNull()?.value?.first()
        val best = ofType.firstOrNull { it.candidate.account == account } ?: ofType.first()
        return Decision(
            best.copy(candidate = best.candidate.copy(account = account)),
            uncertain = false, votes = group.size, readings = picks.size,
        )
    }
}
