package com.scanpesa.app.parser

import com.scanpesa.app.model.Candidate
import com.scanpesa.app.model.PaymentType

/** One recognised word and its box in photo pixels. */
data class OcrWord(val text: String, val box: Box)

/** One recognised line (its words, left to right). */
data class OcrLine(val words: List<OcrWord>)

/**
 * A tappable number on the photo.
 * @param value cleaned number ("247247", "0712345678") or the account text ("SCH-2041A")
 * @param candidate the parsed payment it belongs to, if any
 * @param isAccount true when this box is the account of [candidate] (tapping it selects that paybill)
 */
data class NumberToken(val value: String, val box: Box, val candidate: Candidate?, val isAccount: Boolean = false)

/**
 * Finds every payment-like number on a photo so the user can tap the right one, and works out what
 * a tap means. Pure Kotlin, unit-tested.
 */
object PhotoTokens {

    private val PAYBILL_KW = Regex("""pay\s*-?\s*b[il1][l1]{2}|business\s*n|biz\s*n|\bp\.?b\b""")
    private val TILL_KW = Regex("""\bt[il1][l1]{2}\b|buy\s*goods""")

    /** Valid payment numbers: 5–7 digit paybill/till, or a Kenyan mobile number. */
    fun cleanNumber(digits: String): String? = when {
        digits.length in 5..7 -> digits
        digits.length == 10 && digits[0] == '0' && (digits[1] == '7' || digits[1] == '1') -> digits
        digits.length == 12 && digits.startsWith("254") && (digits[3] == '7' || digits[3] == '1') -> "0" + digits.substring(3)
        digits.length == 9 && digits[0] == '7' -> "0$digits"
        else -> null
    }

    private fun isDigitWord(norm: String): Boolean {
        val d = norm.count { it.isDigit() }
        return d > 0 && d * 10 >= norm.count { it.isLetterOrDigit() } * 7 // ≥70% digits
    }

    private fun union(a: Box, b: Box) = Box(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))

    fun extract(lines: List<OcrLine>, candidates: List<Candidate>): List<NumberToken> {
        val out = mutableListOf<NumberToken>()
        val accounts = candidates.mapNotNull { c -> c.account?.let { it.uppercase() to c } }

        for (line in lines) {
            // 1) Runs of consecutive digit words ("0722 123 456", "5 3 2 4 1 1", "123456 0712 345 678").
            val run = mutableListOf<Pair<String, Box>>()
            fun flush() {
                if (run.isEmpty()) return
                fun joined(i: Int, j: Int) = (i..j).joinToString("") { run[it].first }
                fun box(i: Int, j: Int) = (i..j).map { run[it].second }.reduce(::union)
                // Whole run first; otherwise split greedily into the longest valid numbers.
                val whole = cleanNumber(joined(0, run.lastIndex))
                if (whole != null) {
                    out += token(whole, box(0, run.lastIndex), candidates)
                } else {
                    var i = 0
                    while (i < run.size) {
                        val j = (run.lastIndex downTo i).firstOrNull { cleanNumber(joined(i, it)) != null }
                        if (j == null) { i++; continue }
                        out += token(cleanNumber(joined(i, j))!!, box(i, j), candidates)
                        i = j + 1
                    }
                }
                run.clear()
            }
            for (w in line.words) {
                val norm = MpesaParser.normalize(w.text)
                if (isDigitWord(norm)) {
                    run += norm.filter { it.isDigit() } to w.box
                } else {
                    flush()
                    // 2) Digits glued to letters ("PB:247247", "Till#123456").
                    Regex("""\d{5,12}""").findAll(norm).forEach { m ->
                        cleanNumber(m.value)?.let { out += token(it, w.box, candidates) }
                    }
                }
                // 3) Account values (may be alphanumeric).
                val up = w.text.trim().trim(':', '.', ',').uppercase()
                accounts.firstOrNull { (acc, _) -> acc.length >= 2 && (up == acc || up.endsWith(acc) && up.length - acc.length <= 4) }
                    ?.let { (acc, c) -> if (out.none { it.box == w.box }) out += NumberToken(acc, w.box, c, isAccount = true) }
            }
            flush()
        }
        // The same number can be found twice (e.g. group + glued); keep the first.
        return out.distinctBy { it.value to it.box }
    }

    private fun token(value: String, box: Box, candidates: List<Candidate>): NumberToken {
        val c = candidates.firstOrNull { it.number == value }
        val acc = candidates.firstOrNull { it.account?.filter { ch -> ch.isDigit() } == value && it.number != value }
        return if (c == null && acc != null) NumberToken(value, box, acc, isAccount = true) else NumberToken(value, box, c)
    }

    /** What tapping [t] means as a payment (parser result if it has one, else a sensible guess). */
    fun candidateFor(t: NumberToken, fullText: String): Candidate {
        t.candidate?.let { return it }
        val v = t.value
        if (v.length == 10) return Candidate(PaymentType.SEND_MONEY, v, typeConfirmed = false) // asks Send / Pochi
        val norm = MpesaParser.normalize(fullText)
        val type = if (PAYBILL_KW.containsMatchIn(norm) && !TILL_KW.containsMatchIn(norm)) PaymentType.PAYBILL else PaymentType.TILL
        return Candidate(type, v) // user can switch type on the Confirm screen
    }

    /**
     * Pre-selected box. One payment found: that one. Several: the single Paybill/Till if there is
     * exactly one (phone numbers on signs are usually "Enquiries / Call"); otherwise let the user tap.
     */
    fun defaultSelection(tokens: List<NumberToken>, candidates: List<Candidate>): NumberToken? {
        val pick = candidates.singleOrNull()
            ?: candidates.filter { it.type == PaymentType.PAYBILL || it.type == PaymentType.TILL }.singleOrNull()
            ?: return null
        return tokens.firstOrNull { it.candidate == pick && !it.isAccount }
    }

    /** Where a photo of [imgW]x[imgH] lands when shown "fit" (whole photo visible) in [viewW]x[viewH]. */
    fun fit(imgW: Int, imgH: Int, viewW: Float, viewH: Float): Triple<Float, Float, Float> {
        val s = minOf(viewW / imgW, viewH / imgH)
        return Triple(s, (viewW - imgW * s) / 2f, (viewH - imgH * s) / 2f)
    }

    fun mapFit(b: Box, imgW: Int, imgH: Int, viewW: Float, viewH: Float): Box {
        val (s, dx, dy) = fit(imgW, imgH, viewW, viewH)
        return Box(b.left * s + dx, b.top * s + dy, b.right * s + dx, b.bottom * s + dy)
    }

    /** Token under a tap at ([x],[y]) in view pixels ([slop] px of tolerance), smallest box wins. */
    fun hit(tokens: List<NumberToken>, x: Float, y: Float, imgW: Int, imgH: Int, viewW: Float, viewH: Float, slop: Float): NumberToken? =
        tokens.map { it to mapFit(it.box, imgW, imgH, viewW, viewH) }
            .filter { (_, b) -> x >= b.left - slop && x <= b.right + slop && y >= b.top - slop && y <= b.bottom + slop }
            .minByOrNull { (_, b) -> (b.right - b.left) * (b.bottom - b.top) }?.first
}
