package com.scanpesa.app.parser

import com.scanpesa.app.model.PaymentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoTokensTest {

    /** Builds a line of words laid out left to right on row [row]. */
    private fun line(row: Int, vararg words: String): OcrLine {
        var x = 0f
        return OcrLine(words.map { w ->
            val b = Box(x, row * 100f, x + w.length * 20f, row * 100f + 60f)
            x += w.length * 20f + 15f
            OcrWord(w, b)
        })
    }

    private fun text(lines: List<OcrLine>) = lines.joinToString("\n") { l -> l.words.joinToString(" ") { it.text } }

    @Test fun paybillSignGivesNumberAndAccountTokens() {
        val lines = listOf(line(0, "LIPA", "NA", "M-PESA"), line(1, "PAYBILL", "NO:", "247247"), line(2, "ACCOUNT", "NO:", "0712", "345", "678"))
        val cands = MpesaParser.parse(text(lines))
        val tokens = PhotoTokens.extract(lines, cands)
        val biz = tokens.single { it.value == "247247" }
        assertEquals(PaymentType.PAYBILL, biz.candidate?.type)
        assertFalse(biz.isAccount)
        val acc = tokens.single { it.value == "0712345678" }
        assertTrue("account box selects the paybill", acc.isAccount)
        assertEquals(biz, PhotoTokens.defaultSelection(tokens, cands))
    }

    @Test fun tillAndPhoneOnOneLineStaySeparate() {
        val lines = listOf(line(0, "Till", "123456", "0712", "345", "678"))
        val tokens = PhotoTokens.extract(lines, MpesaParser.parse(text(lines)))
        assertEquals(setOf("123456", "0712345678"), tokens.map { it.value }.toSet())
    }

    @Test fun gluedAndNoisyDigitsAreFound() {
        val lines = listOf(line(0, "PB:247247"), line(1, "O7l2", "345", "678"))
        val tokens = PhotoTokens.extract(lines, emptyList())
        assertEquals(setOf("247247", "0712345678"), tokens.map { it.value }.toSet())
    }

    @Test fun pricesAndDatesAreNotTokens() {
        val lines = listOf(line(0, "KSh", "50"), line(1, "12-05-2024"), line(2, "Open", "8am"))
        assertTrue(PhotoTokens.extract(lines, emptyList()).isEmpty())
    }

    @Test fun tappingAnUnparsedNumberGuessesSensibly() {
        val t5 = NumberToken("400200", Box(0f, 0f, 1f, 1f), null)
        assertEquals(PaymentType.PAYBILL, PhotoTokens.candidateFor(t5, "Pay Bill 400200").type)
        assertEquals(PaymentType.TILL, PhotoTokens.candidateFor(t5, "Lipa na M-PESA 400200").type)
        val phone = PhotoTokens.candidateFor(NumberToken("0712345678", Box(0f, 0f, 1f, 1f), null), "call")
        assertFalse("phone asks Send Money or Pochi", phone.typeConfirmed)
    }

    /** Real OCR output from a phone photo of a printed sign (S22, 2026-11-26). */
    @Test fun realPhotoPaybillIsPreselectedOverEnquiriesPhone() {
        val lines = listOf(
            line(0, "LIPA", "NA", "M-PESA"), line(1, "PAYBILL", "NO247247"), line(2, "ACCOUNT", "NC556677"),
            line(3, "Enguiries:", "0712", "345", "678"), line(4, "Sugar", "1kg", "KSh", "180"),
        )
        val cands = MpesaParser.parse(text(lines))
        val pay = cands.first { it.type == PaymentType.PAYBILL }
        assertEquals("247247", pay.number)
        assertEquals("556677", pay.account)
        val tokens = PhotoTokens.extract(lines, cands)
        assertEquals(setOf("247247", "556677", "0712345678"), tokens.map { it.value }.toSet())
        assertEquals("247247", PhotoTokens.defaultSelection(tokens, cands)?.value)
    }

    @Test fun noDefaultWhenSeveralPayments() {
        val lines = listOf(line(0, "Paybill", "222111"), line(1, "Till", "333444"))
        val cands = MpesaParser.parse(text(lines))
        assertEquals(2, cands.size)
        assertNull(PhotoTokens.defaultSelection(PhotoTokens.extract(lines, cands), cands))
    }

    @Test fun fitMappingAndHitTesting() {
        // 4000x3000 photo shown in a 1000x1000 view: scale 0.25, letterboxed 125px top and bottom.
        val (s, dx, dy) = PhotoTokens.fit(4000, 3000, 1000f, 1000f)
        assertEquals(0.25f, s); assertEquals(0f, dx); assertEquals(125f, dy)
        val t = NumberToken("247247", Box(400f, 400f, 1200f, 600f), null)
        assertNotNull(PhotoTokens.hit(listOf(t), 200f, 250f, 4000, 3000, 1000f, 1000f, slop = 0f))
        assertNull(PhotoTokens.hit(listOf(t), 900f, 900f, 4000, 3000, 1000f, 1000f, slop = 0f))
    }
}
