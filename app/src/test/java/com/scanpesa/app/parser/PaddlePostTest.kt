package com.scanpesa.app.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaddlePostTest {

    @Test fun twoTextRegionsBecomeTwoBoxesInReadingOrder() {
        val w = 40; val h = 20
        val prob = FloatArray(w * h)
        fun fill(l: Int, t: Int, r: Int, b: Int, v: Float) { for (y in t..b) for (x in l..r) prob[y * w + x] = v }
        fill(5, 12, 30, 15, 0.9f)  // lower line
        fill(2, 2, 20, 5, 0.8f)    // upper line
        fill(35, 2, 36, 3, 0.9f)   // 2x2 speck: too small
        val boxes = PaddlePost.boxes(prob, w, h)
        assertEquals(2, boxes.size)
        assertTrue("upper line first", boxes[0].top < boxes[1].top)
        assertTrue("grown beyond the core", boxes[0].left < 2 && boxes[0].right > 20)
    }

    @Test fun weakRegionsAreDropped() {
        val w = 20; val h = 10
        val prob = FloatArray(w * h) { 0.4f } // above pixel thresh, below box thresh
        assertTrue(PaddlePost.boxes(prob, w, h).isEmpty())
    }

    @Test fun ctcCollapsesRepeatsAndBlanks() {
        val keys = listOf("0", "1", "2", "4", "7")
        val classes = keys.size + 2 // blank + keys + space
        // steps: 2 2 blank 4 7 7 blank space 2
        val seq = listOf(3, 3, 0, 4, 5, 5, 0, 6, 3)
        val scores = FloatArray(seq.size * classes)
        seq.forEachIndexed { s, c -> scores[s * classes + c] = 0.9f }
        val (text, conf) = PaddlePost.ctc(scores, seq.size, classes, keys)
        assertEquals("247 2", text)
        assertEquals(0.9f, conf, 1e-4f)
    }

    @Test fun wordsGetProportionalBoxes() {
        val ws = PaddlePost.words("PAYBILL 247247", Box(0f, 0f, 140f, 20f))
        assertEquals(listOf("PAYBILL", "247247"), ws.map { it.text })
        assertEquals(80f, ws[1].box.left, 0.01f)
    }
}
