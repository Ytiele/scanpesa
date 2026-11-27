package com.scanpesa.app.parser

import com.scanpesa.app.model.Candidate
import com.scanpesa.app.model.PaymentType

/**
 * Pure-Kotlin parser that turns (messy) OCR text from signs, stickers and receipts into M-PESA
 * payment candidates. No Android dependencies, so it is fully unit-tested on the JVM.
 */
object MpesaParser {

    /** How far (in characters of normalised text) a number may sit after a keyword. */
    private const val AFTER_WINDOW = 45
    /** How far a number may sit before a keyword ("247247 paybill"). */
    private const val BEFORE_WINDOW = 18

    /** Letters OCR (especially of handwriting) reads instead of digits. Only applied inside digit runs. */
    private val CONFUSABLES = mapOf(
        'o' to '0', 'i' to '1', 'l' to '1', 's' to '5', 'b' to '8',
        'z' to '2', 'g' to '9', 'q' to '9', 't' to '7', 'd' to '0',
    )

    private val PAYBILL_KW = Regex(
        """pay\s*-?\s*b[il1|][l1|]{2}|business\s*(?:n[o0]\.?|number|num|#)|biz\s*(?:n[o0]|number)|\bp\.?b\b"""
    )
    private val TILL_KW = Regex("""\bt[il1|][l1|]{2}\b|buy\s*goods""")
    private val LIPA_KW = Regex("""lipa\s*na\s*m\s*-?\s*pesa""")
    private val POCHI_KW = Regex("""pochi""")
    private val SEND_KW = Regex("""send\s*money|tuma\s*pesa""")

    /** Account keyword followed by its value (value may be alphanumeric, may contain a dash). */
    private val ACCOUNT_RE = Regex(
        """(?:\baccount|\bacct|\bacc|\ba/c)(?![a-mo-z])\.?\s*(?:n[o0c]\.?|number|num|#)?\s*[:#.\-=]?\s*([a-z0-9][a-z0-9\-]{0,23}(?: \d{1,8})*)"""
    )
    private val ACCOUNT_STOPWORDS = setOf("no", "n0", "number", "num", "name", "names")

    /** "Business name: Mama Mboga Shop" is never used as the account (or anything else). */
    private val BUSINESS_NAME_RE = Regex(
        """(?:business|biz|shop|company|merchant|account)\s*name\s*[:#.\-=]?\s*([^\n]*)"""
    )

    private val PHONE_RE = Regex("""(?<![\d+])(?:\+?254|0)([17]\d{8})(?!\d)""")
    /** Paybill/Till numbers: 5-7 digits, never starting with 0 (that would be a misread phone). */
    private val BUSINESS_NUM_RE = Regex("""(?<!\d)[1-9]\d{4,6}(?!\d)""")

    // ------------------------------------------------------------------ normalisation

    /**
     * Lowercase, fix OCR letter/digit confusions inside digit runs, collapse whitespace and join
     * digit groups that were split by spaces or dashes ("0722 123 456" -> "0722123456").
     */
    fun normalize(raw: String): String {
        var t = raw.lowercase()
            .replace('–', '-').replace('—', '-')
            .replace(Regex("""[\r\n\t]+"""), " \n ")
        // "|" inside a number is a box edge on boxed-digit signs, never a 1: treat it as a gap.
        t = t.replace('|', ' ')
        // Handwritten 4 is often read as '+' inside a number ("3+68500" -> 3468500).
        t = t.replace(Regex("""(?<=\d)\+(?=\d{2})"""), "4")
        t = fixConfusables(t)
        // Drop OCR junk characters (after the digit fixes, which may use '|').
        t = t.replace(Regex("""[^a-z0-9+/\-.:#&'\n ]"""), " ")
        t = t.replace(Regex("""[  ]+"""), " ")
        t = joinPhones(t)
        t = joinDigitGroups(t)
        return t.replace(Regex("""\s*\n\s*"""), "\n").replace(Regex(" +"), " ").trim()
    }

    /** Replace O/I/l/S/B in tokens that consist only of digits and confusable letters (mostly digits). */
    private fun fixConfusables(text: String): String {
        val token = Regex("""[a-z0-9|]+""")
        return token.replace(text) { m ->
            val s = m.value
            val digits = s.count { it.isDigit() }
            val allOk = s.all { it.isDigit() || it in CONFUSABLES }
            if (allOk && digits >= 2 && digits * 3 >= s.length) {
                s.map { CONFUSABLES[it] ?: it }.joinToString("")
            } else s
        }
    }

    /** "+254 722 123 456", "0722-123-456", "254 7 2 2 ..." -> "0722123456". */
    private fun joinPhones(text: String): String {
        val spaced = Regex("""(?<![\d+])(?:\+\s?254|254|0)[ \-./]?([17](?:[ \-./]?\d){8})(?![\d])""")
        val joined = spaced.replace(text) { m -> "0" + m.groupValues[1].filter { it.isDigit() } }
        // Handwritten numbers often drop the leading 0: "722 123 456" -> "0722123456".
        val noZero = Regex("""(?<![\d+.])(7(?:[ \-./]?\d){8})(?![\d])""")
        return noZero.replace(joined) { m -> "0" + m.groupValues[1].filter { it.isDigit() } }
    }

    /**
     * Join runs of short digit groups ("522 522", "5 3 2 4 1 1", "12-34-56") when the joined
     * length looks like a business/till number (5-7 digits). Long groups are never merged, so
     * "123456 0712345678" stays two numbers.
     */
    private fun joinDigitGroups(text: String): String {
        val run = Regex("""(?<![\d])\d{1,4}(?:[ \-]\d{1,4})+(?![\d])""")
        return run.replace(text) { m ->
            val groups = m.value.split(' ', '-')
            // Boxed digits ("2 0 1 4 3 6 1 1", "20 1 43 6 1 1"): many 1-2 digit pieces are one number.
            if (groups.size >= 4 && groups.all { it.length <= 2 } && groups.sumOf { it.length } in 5..14) {
                return@replace groups.joinToString("")
            }
            // Find the longest prefix of groups whose joined length is 5..7.
            val joined = StringBuilder()
            var out: String? = null
            var used = 0
            for ((i, g) in groups.withIndex()) {
                joined.append(g)
                if (joined.length in 5..7) { out = joined.toString(); used = i + 1 }
                if (joined.length > 7) break
            }
            if (out == null) m.value
            else (listOf(out) + groups.drop(used)).joinToString(" ")
        }
    }

    // ------------------------------------------------------------------ parsing

    private data class Num(val value: String, val start: Int, val end: Int)

    /** Parse OCR text into candidates, most specific (paybill/till) first. Duplicates removed. */
    fun parse(raw: String): List<Candidate> {
        val text = normalize(raw)
        if (text.isBlank()) return emptyList()

        val used = mutableSetOf<IntRange>() // number spans already assigned
        val out = mutableListOf<Candidate>()

        val phones = PHONE_RE.findAll(text).map { Num("0" + it.groupValues[1], it.range.first, it.range.last + 1) }.toList()
        val phoneSpans = phones.map { it.start until it.end }
        val business = BUSINESS_NUM_RE.findAll(text)
            .map { Num(it.value, it.range.first, it.range.last + 1) }
            .filter { n -> phoneSpans.none { n.start in it } }
            .toList()

        // Business/shop name text is never an account.
        val nameMatches = BUSINESS_NAME_RE.findAll(text).toList()
        val nameSpans = nameMatches.map { it.range }
        val nameWords = nameMatches.flatMap { it.groupValues[1].split(' ') }.filter { it.length > 1 }.toSet()

        // Account values (so they are not re-used as phones / tills)
        val accounts = ACCOUNT_RE.findAll(text).mapNotNull { m ->
            // "40 0 7047 7" -> 40070477, but only for numbers: "o2 47" stays "o2" (a scrap).
            val v = m.groupValues[1].trim('-').let { raw ->
                val first = raw.substringBefore(' ')
                if (raw.contains(' ') && first.all { it.isDigit() }) raw.replace(" ", "") else first
            }
            val valueStart = m.groups[1]!!.range.first
            // Numeric accounts may be 3+ digits; anything with letters needs 4+ ("EE2" is a scrap).
            if (v.length < 3 || (v.length < 4 && !v.all { it.isDigit() }) || v in ACCOUNT_STOPWORDS) null
            else if (nameSpans.any { valueStart in it }) null
            else if (v in nameWords && v.none { it.isDigit() }) null
            else Triple(m.range.first, m.groups[1]!!.range, v)
        }.toList()
        val accountSpans = accounts.map { it.second }

        fun isAccountValue(n: Num) = accountSpans.any { n.start >= it.first && n.start <= it.last }

        val hasPaybillKw = PAYBILL_KW.containsMatchIn(text)

        /**
         * Some signs print the label UNDER the value ("20252025 / ACCOUNT NUMBER / SAMPLE POSTER").
         * If the account label is followed by words only but the line above it is a number, use that.
         */
        fun accountAbove(paybill: String): String? {
            val lines = text.split("\n")
            for (i in 1 until lines.size) {
                if (!Regex("""\b(account|acc|a/c)\b""").containsMatchIn(lines[i])) continue
                val above = lines[i - 1].trim()
                if (Regex("""[a-z0-9\-]{4,24}""").matches(above) && above.any { it.isDigit() } && above != paybill) return above
            }
            return null
        }

        // PAYBILL
        for (kw in PAYBILL_KW.findAll(text)) {
            val n = nearestNumber(business, kw.range, used, ::isAccountValue) ?: continue
            used += n.start until n.end
            val acc = accounts.firstOrNull { it.first >= n.end && it.first - n.end <= 60 }
                ?: accounts.firstOrNull { it.first >= kw.range.last && it.first - kw.range.last <= 80 }
            acc?.let { used += it.second }
            var account = acc?.third?.let(::cleanAccount)
            if (account == null || account.none { it.isDigit() }) accountAbove(n.value)?.let { account = cleanAccount(it) }
            out += Candidate(PaymentType.PAYBILL, n.value, account)
        }

        // TILL ("lipa na m-pesa" only counts when no paybill keyword exists)
        val tillKws = TILL_KW.findAll(text).toMutableList()
        if (!hasPaybillKw) tillKws += LIPA_KW.findAll(text)
        for (kw in tillKws.sortedBy { it.range.first }) {
            val n = nearestNumber(business, kw.range, used, ::isAccountValue) ?: continue
            used += n.start until n.end
            out += Candidate(PaymentType.TILL, n.value)
        }

        // PHONES
        for (p in phones) {
            if (used.any { p.start in it }) continue
            if (accountSpans.any { p.start in it }) continue
            val near = text.substring((p.start - 45).coerceAtLeast(0), p.start)
            val c = when {
                POCHI_KW.containsMatchIn(near) -> Candidate(PaymentType.POCHI, p.value)
                SEND_KW.containsMatchIn(near) -> Candidate(PaymentType.SEND_MONEY, p.value)
                else -> Candidate(PaymentType.SEND_MONEY, p.value, typeConfirmed = false)
            }
            out += c
        }

        return out.distinctBy { it.type to it.number }
    }

    private fun nearestNumber(nums: List<Num>, kw: IntRange, used: Set<IntRange>, isAccount: (Num) -> Boolean): Num? {
        val free = nums.filter { n -> used.none { n.start in it } && !isAccount(n) }
        val after = free.filter { it.start > kw.last && it.start - kw.last <= AFTER_WINDOW }.minByOrNull { it.start }
        if (after != null) return after
        return free.filter { it.end <= kw.first && kw.first - it.end <= BEFORE_WINDOW }.maxByOrNull { it.end }
    }

    /** Uppercase the account and fix O/I/S/B only when sandwiched between digits ("12O45"). */
    private fun cleanAccount(v: String): String {
        val chars = v.toCharArray()
        for (i in 1 until chars.size - 1) {
            val fix = CONFUSABLES[chars[i]]
            if (fix != null && chars[i - 1].isDigit() && chars[i + 1].isDigit()) chars[i] = fix
        }
        return String(chars).uppercase()
    }
}
