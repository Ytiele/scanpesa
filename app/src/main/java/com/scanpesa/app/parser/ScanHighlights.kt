package com.scanpesa.app.parser

import com.scanpesa.app.model.Candidate

/** A rectangle in pixels (pure Kotlin so it can be unit-tested). */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Decides which recognised text lines to outline on the camera preview, and maps their boxes from
 * the analysed camera image onto the on-screen preview.
 */
object ScanHighlights {

    enum class Kind {
        /** The number / account that will be used: solid green box. */
        VALUE,
        /** A keyword next to it ("Paybill", "Till", "Account"...): faint box. */
        KEYWORD,
    }

    private val KEYWORDS = Regex(
        """pay\s*-?\s*b[il1][l1]{2}|business\s*n|biz\s*n|\bt[il1][l1]{2}\b|buy\s*goods|lipa\s*na|acc(oun)?t?\b|a/c|pochi|send\s*money|\bp\.?b\b"""
    )

    /** What [lineText] is, given the [candidates] parsed from the whole frame; null = don't outline. */
    fun classify(lineText: String, candidates: List<Candidate>): Kind? {
        val norm = MpesaParser.normalize(lineText)
        val digits = norm.filter { it.isDigit() }
        for (c in candidates) {
            val n = c.number
            if (digits.length >= 5) {
                if (n in digits) return Kind.VALUE
                // phone written as +254 7.. / 7.. without the leading 0
                if (n.length == 10 && n.substring(1) in digits) return Kind.VALUE
                // number split over two lines: this line holds a big chunk of it
                if (digits.length >= 6 && digits in n) return Kind.VALUE
            }
            c.account?.let { acc ->
                if (acc.length >= 2 && acc.lowercase() in norm.replace(" ", "")) return Kind.VALUE
            }
        }
        return if (KEYWORDS.containsMatchIn(norm)) Kind.KEYWORD else null
    }

    /**
     * Maps a box from the (upright) analysed image of size [imageW]x[imageH] onto a preview of size
     * [viewW]x[viewH] shown with FILL_CENTER scaling (scaled to cover, centred, edges cropped).
     */
    fun mapToView(b: Box, imageW: Int, imageH: Int, viewW: Int, viewH: Int): Box {
        if (imageW <= 0 || imageH <= 0) return b
        val s = maxOf(viewW.toFloat() / imageW, viewH.toFloat() / imageH)
        val dx = (viewW - imageW * s) / 2f
        val dy = (viewH - imageH * s) / 2f
        return Box(b.left * s + dx, b.top * s + dy, b.right * s + dx, b.bottom * s + dy)
    }

    /** One-line summary of what a capture would use, e.g. "Paybill 247247 · Acc 556677". */
    fun summary(candidates: List<Candidate>): String = candidates.joinToString("   |   ") { c ->
        val type = if (c.typeConfirmed) c.type.label.substringBefore(" (") else "Phone"
        "$type ${c.number}" + (c.account?.let { " · Acc $it" } ?: "")
    }
}
