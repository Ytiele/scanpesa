package com.scanpesa.app.parser

import com.scanpesa.app.parser.ScanHighlights.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanHighlightsTest {

    private val sign = "LIPA NA M-PESA\nPAYBILL NO: 247247\nACCOUNT NO: 0712 345 678\nThank you"
    private val cands = MpesaParser.parse(sign)

    @Test fun numberLinesAreValues() {
        assertEquals(Kind.VALUE, ScanHighlights.classify("PAYBILL NO: 247247", cands))
        assertEquals(Kind.VALUE, ScanHighlights.classify("ACCOUNT NO: 0712 345 678", cands))
    }

    @Test fun keywordOnlyLinesAreKeywords() {
        assertEquals(Kind.KEYWORD, ScanHighlights.classify("LIPA NA M-PESA", cands))
        assertEquals(Kind.KEYWORD, ScanHighlights.classify("Till Number", cands))
    }

    @Test fun otherTextIsNotOutlined() {
        assertNull(ScanHighlights.classify("Thank you", cands))
        assertNull(ScanHighlights.classify("Open 8am - 6pm", cands))
    }

    @Test fun ocrNoiseInNumberStillMatches() {
        val c = MpesaParser.parse("Till No 5 3 2 4 1 1")
        assertEquals(Kind.VALUE, ScanHighlights.classify("Till: 53241l", c))
    }

    @Test fun phoneWithCountryCodeMatches() {
        val c = MpesaParser.parse("Pochi la Biashara 0712 345 678")
        assertEquals(Kind.VALUE, ScanHighlights.classify("+254 712 345 678", c))
    }

    @Test fun fillCenterMappingScalesAndCentres() {
        // 480x640 upright image shown in a 960x1280 view: exact 2x scale, no offset.
        assertEquals(Box(20f, 40f, 200f, 100f), ScanHighlights.mapToView(Box(10f, 20f, 100f, 50f), 480, 640, 960, 1280))
        // Wider view than image: scale by width, crop top/bottom equally.
        val m = ScanHighlights.mapToView(Box(0f, 0f, 480f, 640f), 480, 640, 960, 1000)
        assertEquals(0f, m.left); assertEquals(960f, m.right)
        assertEquals(-140f, m.top); assertEquals(1140f, m.bottom)
    }

    @Test fun summaryReadsNaturally() {
        assertEquals("Paybill 247247 · Acc 0712345678", ScanHighlights.summary(cands))
    }
}
