package com.scanpesa.app.parser

import com.scanpesa.app.model.Candidate
import com.scanpesa.app.model.PaymentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutConsensusTest {

    private fun w(t: String, x: Float, y: Float, wd: Float = 40f, h: Float = 60f) = OcrWord(t, Box(x, y, x + wd, y + h))

    /** OCR returned boxed till digits as three "lines" out of order: 8 8 4 8 8 | 83 | 4. */
    @Test fun boxedDigitsSplitAcrossLinesAreRebuilt() {
        val words = listOf(
            w("BUY", 0f, 0f, 80f), w("GOODS", 100f, 0f, 120f), w("TILL", 240f, 0f, 80f), w("NUMBER", 340f, 0f, 140f),
            // arrives out of order, as separate "lines": 4 | 3 | 8 8 4 8 8
            w("4", 420f, 99f), w("3", 350f, 101f),
            w("8", 0f, 100f), w("8", 70f, 100f), w("4", 140f, 102f), w("8", 210f, 98f), w("8", 280f, 100f),
        )
        val text = Layout.text(Layout.rows(words))
        assertEquals("BUY GOODS TILL NUMBER\n8848834", text)
        assertEquals("8848834", MpesaParser.parse(text).single().number)
    }

    @Test fun separateLongNumbersOnOneRowStaySeparate() {
        val words = listOf(w("Till", 0f, 0f, 80f), w("123456", 100f, 0f, 160f), w("0712", 300f, 0f), w("345", 360f, 0f), w("678", 420f, 0f))
        assertEquals("Till 123456 0712345678", Layout.text(Layout.rows(words)))
    }

    private fun pick(type: PaymentType, n: String, acc: String? = null) =
        PhotoTokens.AutoPick(Candidate(type, n, acc), null, null)

    @Test fun majorityWinsAndMisreadIsOutvoted() {
        val d = Consensus.decide(listOf(
            pick(PaymentType.PAYBILL, "222111", "5910057"), pick(PaymentType.PAYBILL, "222011", "5910057"),
            pick(PaymentType.PAYBILL, "222111", "5910057"), pick(PaymentType.PAYBILL, "222111", null), null, null,
        ))
        assertFalse(d.uncertain)
        assertEquals("222111", d.pick!!.candidate.number)
        assertEquals("5910057", d.pick!!.candidate.account)
    }

    @Test fun disagreementIsUncertainNotAGuess() {
        val d = Consensus.decide(listOf(pick(PaymentType.TILL, "88488"), pick(PaymentType.TILL, "8848834"), null))
        assertTrue(d.uncertain)
        assertNull(d.pick)
    }

    @Test fun tiedAccountsAreUnreadableNotGuessed() {
        val d = Consensus.decide(listOf(pick(PaymentType.PAYBILL, "625625", "2014361"), pick(PaymentType.PAYBILL, "625625", "20143611")))
        assertEquals("625625", d.pick!!.candidate.number)
        assertNull(d.pick!!.candidate.account)
    }

    @Test fun disagreeingAccountsMeanNoAccount() {
        val d = Consensus.decide(listOf(pick(PaymentType.PAYBILL, "542542", "8330071"), pick(PaymentType.PAYBILL, "542542", "833007"),
            pick(PaymentType.PAYBILL, "542542", "833007")))
        assertEquals("542542", d.pick!!.candidate.number)
        assertNull("2 vs 1 is not clear enough", d.pick!!.candidate.account)
    }

    private fun dec(p: PhotoTokens.AutoPick?, unsure: Boolean = false) = Consensus.Decision(p, unsure, if (p == null) 0 else 3, 6)

    @Test fun cleanMlKitAnswerIsNeverOverruled() {
        val mk = pick(PaymentType.TILL, "123456")
        val d = Consensus.combine(dec(mk), listOf(mk), dec(pick(PaymentType.TILL, "23456")))
        assertEquals("123456", d.pick!!.candidate.number)
    }

    @Test fun paddleSuppliesAccountOnlyIfMlKitAlsoSawIt() {
        val mk = pick(PaymentType.PAYBILL, "542542", null)
        val seen = listOf(mk, pick(PaymentType.PAYBILL, "542542", "739449"), pick(PaymentType.PAYBILL, "542542", "7394449"))
        assertEquals("739449", Consensus.combine(dec(mk), seen, dec(pick(PaymentType.PAYBILL, "542542", "739449"))).pick!!.candidate.account)
        assertNull(Consensus.combine(dec(mk), seen, dec(pick(PaymentType.PAYBILL, "542542", "73394"))).pick!!.candidate.account)
    }

    @Test fun paddleSettlesMlKitDoubtOnlyByBackingItsLeadingNumber() {
        // Real case (tilted 20 deg): ML Kit 6x 123456 vs 4x 23456; Paddle read 23456 -> must stay unsure.
        val readings = List(6) { pick(PaymentType.TILL, "123456") } + List(4) { pick(PaymentType.TILL, "23456") }
        assertTrue(Consensus.combine(dec(null, true), readings, dec(pick(PaymentType.TILL, "23456"))).uncertain)
        assertEquals("123456", Consensus.combine(dec(null, true), readings, dec(pick(PaymentType.TILL, "123456"))).pick!!.candidate.number)
    }

    @Test fun paddleAloneOnlyForPhoneNumbers() {
        // Real case: boxed till 123456 read by Paddle as 23456 while ML Kit found nothing.
        assertTrue(Consensus.combine(dec(null), listOf(null, null), dec(pick(PaymentType.TILL, "23456"))).uncertain)
        assertTrue(Consensus.combine(dec(null), listOf(null), dec(pick(PaymentType.PAYBILL, "247247", "238"))).uncertain)
        assertEquals("0712001374", Consensus.combine(dec(null), emptyList(), dec(pick(PaymentType.POCHI, "0712001374"))).pick!!.candidate.number)
    }

    @Test fun singleReadingWithNoConflictIsAccepted() {
        val d = Consensus.decide(listOf(null, pick(PaymentType.POCHI, "0712001374"), null))
        assertFalse(d.uncertain)
        assertEquals("0712001374", d.pick!!.candidate.number)
    }
}

class GlyphsTest {
    private fun g(t: String, x: Float, top: Float, h: Float) = Glyph(t, Box(x, top, x + 20f, top + h))

    /** "40 0|7047|7": the "|" are box edges, taller than the digits inside the boxes. */
    @Test fun boxEdgesAreDropped() {
        val glyphs = listOf(g("0", 0f, 10f, 50f), g("|", 25f, 0f, 75f), g("7", 50f, 10f, 50f), g("0", 75f, 10f, 50f),
            g("4", 100f, 10f, 50f), g("7", 125f, 10f, 50f), g("|", 150f, 0f, 75f), g("7", 175f, 10f, 50f))
        val h = Glyphs.digitHeight(glyphs)
        org.junit.Assert.assertEquals("070477", Glyphs.word(glyphs, h))
    }

    @Test fun realOnesAreKept() {
        val glyphs = listOf(g("2", 0f, 10f, 50f), g("1", 25f, 10f, 52f), g("1", 50f, 9f, 51f), g("3", 75f, 10f, 50f))
        org.junit.Assert.assertEquals("2113", Glyphs.word(glyphs, Glyphs.digitHeight(glyphs)))
    }
}
