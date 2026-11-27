package com.scanpesa.app.parser

/**
 * Pure post-processing for the PaddleOCR (PP-OCRv4) models, kept free of Android so it can be
 * unit-tested:
 *  - [boxes]: turns the text-detection probability map into text-line boxes (DB post-processing,
 *    simplified to axis-aligned boxes: photos are straightened before reading).
 *  - [ctc]: turns the recogniser's per-step class scores into text (greedy CTC decoding).
 */
object PaddlePost {

    /** A detected text line in probability-map pixels, with its mean probability. */
    data class MapBox(val left: Int, val top: Int, val right: Int, val bottom: Int, val score: Float)

    /**
     * Connected regions of `prob > thresh` (4-neighbour), kept if their mean probability is at
     * least [boxThresh], then grown ("unclipped") by area * [unclip] / perimeter as PaddleOCR does,
     * because the model predicts a shrunken core of each text line.
     */
    fun boxes(prob: FloatArray, w: Int, h: Int, thresh: Float = 0.3f, boxThresh: Float = 0.6f, unclip: Float = 1.6f): List<MapBox> {
        val seen = BooleanArray(w * h)
        val out = mutableListOf<MapBox>()
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (seen[start] || prob[start] <= thresh) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var l = w; var t = h; var r = -1; var b = -1; var sum = 0f; var n = 0
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w; val y = p / w
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
                sum += prob[p]; n++
                if (x > 0) { val q = p - 1; if (!seen[q] && prob[q] > thresh) { seen[q] = true; stack[sp++] = q } }
                if (x < w - 1) { val q = p + 1; if (!seen[q] && prob[q] > thresh) { seen[q] = true; stack[sp++] = q } }
                if (y > 0) { val q = p - w; if (!seen[q] && prob[q] > thresh) { seen[q] = true; stack[sp++] = q } }
                if (y < h - 1) { val q = p + w; if (!seen[q] && prob[q] > thresh) { seen[q] = true; stack[sp++] = q } }
            }
            val bw = r - l + 1; val bh = b - t + 1
            if (bw < 3 || bh < 3) continue
            val score = sum / n
            if (score < boxThresh) continue
            val d = (bw * bh * unclip / (2f * (bw + bh))).toInt()
            out += MapBox((l - d).coerceAtLeast(0), (t - d).coerceAtLeast(0), (r + d).coerceAtMost(w - 1), (b + d).coerceAtMost(h - 1), score)
        }
        // Reading order: top to bottom, then left to right.
        return out.sortedWith(compareBy({ it.top / 8 }, { it.left }))
    }

    /**
     * Greedy CTC decoding. [scores] is steps x classes (row-major). Class 0 is the CTC blank,
     * class i (1..keys.size) is keys[i - 1], and the last class is a space. Returns text and the
     * mean confidence of the kept characters.
     */
    fun ctc(scores: FloatArray, steps: Int, classes: Int, keys: List<String>): Pair<String, Float> {
        val sb = StringBuilder()
        var prev = -1; var conf = 0f; var kept = 0
        for (s in 0 until steps) {
            var best = 0; var bestV = scores[s * classes]
            for (c in 1 until classes) { val v = scores[s * classes + c]; if (v > bestV) { bestV = v; best = c } }
            if (best != 0 && best != prev) {
                sb.append(if (best - 1 < keys.size) keys[best - 1] else " ")
                conf += bestV; kept++
            }
            prev = best
        }
        return sb.toString() to (if (kept == 0) 0f else conf / kept)
    }

    /** Splits a line's text into words with boxes proportional to their character positions. */
    fun words(text: String, box: Box): List<OcrWord> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        val perChar = (box.right - box.left) / t.length
        val out = mutableListOf<OcrWord>()
        var i = 0
        while (i < t.length) {
            if (t[i] == ' ') { i++; continue }
            var j = i
            while (j < t.length && t[j] != ' ') j++
            out += OcrWord(t.substring(i, j), Box(box.left + i * perChar, box.top, box.left + j * perChar, box.bottom))
            i = j
        }
        return out
    }
}
