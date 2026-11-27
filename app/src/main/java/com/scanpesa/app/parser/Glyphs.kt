package com.scanpesa.app.parser

/** One recognised character and its box. */
data class Glyph(val text: String, val box: Box)

/**
 * Character-level clean-up. Signs often print each digit in its own box; the box edges get read as
 * "|", "1", "I", "l" or "[" and end up inside the number ("40 0|7047|7" -> 400170477).
 * A box edge is taller than the digits printed inside the boxes, so thin characters that are
 * clearly taller than the line's real digits are dropped.
 */
object Glyphs {

    private val EDGE_LIKE = setOf("|", "1", "I", "l", "[", "]", "!", "/", "\\", "(", ")")

    private fun height(b: Box) = b.bottom - b.top

    /** Median height of the line's unambiguous digits (0, 2-9); 0 if there are none. */
    fun digitHeight(lineGlyphs: List<Glyph>): Float {
        val hs = lineGlyphs.filter { it.text.length == 1 && it.text[0].isDigit() && it.text != "1" }.map { height(it.box) }.sorted()
        return if (hs.isEmpty()) 0f else hs[hs.size / 2]
    }

    /** The word's text with box edges removed. */
    fun word(glyphs: List<Glyph>, lineDigitHeight: Float): String {
        if (lineDigitHeight <= 0f) return glyphs.joinToString("") { it.text }
        return glyphs.filterNot { g -> g.text in EDGE_LIKE && height(g.box) > 1.25f * lineDigitHeight }
            .joinToString("") { it.text }
    }
}
