package com.scanpesa.app.parser

import com.scanpesa.app.model.PaymentType

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

    /** Length of an all-digit word (after OCR fixes), else null. */
    private fun digitLen(t: String): Int? {
        val n = MpesaParser.normalize(t)
        if (n.isEmpty() || n.length > 14 || !n.all { it.isDigit() }) return null
        return n.length
    }

    /** Text of the rows. Short digit pieces next to each other (boxed digits, "0712 345 678") are joined. */
    fun text(rows: List<OcrLine>): String = rows.joinToString("\n") { row ->
        val sb = StringBuilder()
        row.words.forEachIndexed { i, w ->
            if (i > 0) {
                val prev = row.words[i - 1]
                val a = digitLen(prev.text); val b = digitLen(w.text)
                // Join short digit pieces ("0712 345 678", boxed digits), and a lone 1-2 digit piece
                // next to a longer number (an edge box read separately: "4" "99077" "4").
                val pieces = a != null && b != null && ((a <= 4 && b <= 4) || minOf(a, b) <= 2)
                val join = pieces && (w.box.left - prev.box.right) < 2.5f * maxOf(h(prev.box), h(w.box))
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

    /**
     * Merges ML Kit (primary: [a] and its per-reading [aPicks]) with PaddleOCR ([b]) as a second
     * witness. Paddle never overrules a clean ML Kit answer; it only:
     *  - supplies an account ML Kit couldn't settle, if one of ML Kit's own readings saw exactly it;
     *  - settles an ML Kit "not sure", if Paddle agrees with ML Kit's most-voted number;
     *  - provides the number when ML Kit found nothing at all (its account is not trusted alone).
     */
    fun combine(a: Decision, aPicks: List<PhotoTokens.AutoPick?>, b: Decision): Decision {
        val pa = a.pick; val pb = b.pick
        val readings = a.readings + b.readings
        val norm = { s: String? -> s?.uppercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() } }
        val seenByA = { n: String, acc: String? -> aPicks.any { it?.candidate?.number == n && (acc == null || norm(it.candidate.account) == norm(acc)) } }
        if (pa != null) {
            if (pa.candidate.account == null && pb != null && pb.candidate.number == pa.candidate.number) {
                val acc = pb.candidate.account
                if (acc != null && acc.any(Char::isDigit) && seenByA(pa.candidate.number, acc)) {
                    return a.copy(pick = pa.copy(candidate = pa.candidate.copy(account = acc)), readings = readings)
                }
            }
            return a.copy(readings = readings)
        }
        if (pb == null) return Decision(null, uncertain = a.uncertain || b.uncertain, votes = 0, readings = readings)
        if (a.uncertain) {
            // Paddle may only back ML Kit's most-voted number (never its minority reading).
            val top = aPicks.filterNotNull().groupBy { it.candidate.number }.maxByOrNull { it.value.size }?.key
            if (pb.candidate.number != top) return Decision(null, uncertain = true, votes = 0, readings = readings)
            val acc = pb.candidate.account?.takeIf { seenByA(pb.candidate.number, it) }
            return Decision(pb.copy(candidate = pb.candidate.copy(account = acc)), false, b.votes, readings)
        }
        // ML Kit found nothing: trust Paddle alone only for phone numbers (Send Money / Pochi). A
        // dropped digit turns a phone into an invalid number, but a 5-7 digit Till/Paybill with a
        // dropped digit still looks valid, so those need ML Kit's support.
        val phone = pb.candidate.type == PaymentType.SEND_MONEY || pb.candidate.type == PaymentType.POCHI
        if (!phone) return Decision(null, uncertain = true, votes = 0, readings = readings)
        return Decision(pb.copy(candidate = pb.candidate.copy(account = null)), false, b.votes, readings)
    }

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
        // The account must be seen the same way at least twice, and no reading may have seen an
        // account of a different length (that reading dropped or added a digit somewhere).
        // Otherwise it's unreadable: Go stays off and the user retakes.
        // If any reading (also those that misread the business number) saw a LONGER account than the
        // majority, the majority probably dropped a digit -> unreadable. Shorter scraps are ignored.
        // Exception: a single stray longer reading (a doubled digit on boxed signs) is outvoted when
        // at least 4 readings agree and outnumber the longer ones 4 to 1.
        val majority = accGroups.firstOrNull()?.key
        val longer = if (majority == null) 0 else valid.count { (it.candidate.account?.let(norm)?.length ?: 0) > majority.length }
        val outvoted = longer == 0 || (accTop >= 4 && accTop >= 4 * longer)
        // ...and the account must be backed by at least half of the readings that agree on the number
        // (others may have seen it as incomplete, e.g. "73944 o").
        val supported = accTop * 2 >= ofType.size
        val account = if (majority == null || !outvoted || !supported || (accSecond > 0 && accTop < 3 * accSecond)) null
        else accGroups.first().value.first()
        val best = ofType.firstOrNull { it.candidate.account == account } ?: ofType.first()
        return Decision(
            best.copy(candidate = best.candidate.copy(account = account)),
            uncertain = false, votes = group.size, readings = picks.size,
        )
    }
}
