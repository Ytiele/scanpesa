package com.scanpesa.app.ui

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.scanpesa.app.parser.Box
import com.scanpesa.app.parser.OcrLine
import com.scanpesa.app.parser.PaddlePost
import java.nio.FloatBuffer

/**
 * Second OCR engine: PaddleOCR PP-OCRv4 mobile (Apache-2.0), run offline with ONNX Runtime.
 * Models: assets/paddle/det.onnx (finds text lines), rec.onnx (reads them), keys.txt (characters).
 * It makes different mistakes than ML Kit, so its readings join the vote.
 */
object PaddleOcr {
    private const val DET_MAX_SIDE = 960
    private const val REC_H = 48
    private const val REC_MAX_W = 640

    private val env by lazy { OrtEnvironment.getEnvironment() }
    @Volatile private var det: OrtSession? = null
    @Volatile private var rec: OrtSession? = null
    @Volatile private var keys: List<String> = emptyList()

    /** Loads the models once (about 16 MB). Safe to call repeatedly; call off the main thread. */
    @Synchronized
    fun init(ctx: Context) {
        if (det != null) return
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        fun bytes(name: String) = ctx.assets.open("paddle/$name").use { it.readBytes() }
        keys = ctx.assets.open("paddle/keys.txt").bufferedReader().readLines()
        det = env.createSession(bytes("det.onnx"), opts)
        rec = env.createSession(bytes("rec.onnx"), opts)
    }

    /** Reads [bmp] into lines of words (boxes in [bmp] pixels). Blocking: call off the main thread. */
    fun read(ctx: Context, bmp: Bitmap): List<OcrLine> {
        init(ctx)
        val boxes = detect(bmp)
        return boxes.mapNotNull { b ->
            val (text, conf) = recognize(bmp, b)
            if (text.isBlank() || conf < 0.5f) null else OcrLine(PaddlePost.words(text, b))
        }
    }

    /** Text detection: scale so the long side <= 960 (multiples of 32), run, post-process. */
    private fun detect(bmp: Bitmap): List<Box> {
        val s = minOf(1f, DET_MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height))
        val w = maxOf(32, ((bmp.width * s / 32).toInt()) * 32)
        val h = maxOf(32, ((bmp.height * s / 32).toInt()) * 32)
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h).also { scaled.getPixels(it, 0, w, 0, 0, w, h) }
        // PaddleOCR normalisation, channels in B, G, R order (as trained with OpenCV).
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f); val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val data = FloatArray(3 * w * h)
        for (i in px.indices) {
            val c = px[i]
            val bgr = floatArrayOf((c and 0xFF) / 255f, (c shr 8 and 0xFF) / 255f, (c shr 16 and 0xFF) / 255f)
            for (ch in 0..2) data[ch * w * h + i] = (bgr[ch] - mean[ch]) / std[ch]
        }
        val session = det ?: return emptyList()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, h.toLong(), w.toLong())).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val map = (out[0].value as Array<Array<Array<FloatArray>>>)[0][0]
                val prob = FloatArray(w * h); for (y in 0 until h) System.arraycopy(map[y], 0, prob, y * w, w)
                val sx = bmp.width.toFloat() / w; val sy = bmp.height.toFloat() / h
                return PaddlePost.boxes(prob, w, h).map { b ->
                    Box(b.left * sx, b.top * sy, (b.right + 1) * sx, (b.bottom + 1) * sy)
                }
            }
        }
    }

    /** Text recognition of one line: crop, scale to 48 px high, run, CTC-decode. */
    private fun recognize(bmp: Bitmap, b: Box): Pair<String, Float> {
        val l = b.left.toInt().coerceIn(0, bmp.width - 1); val t = b.top.toInt().coerceIn(0, bmp.height - 1)
        val r = b.right.toInt().coerceIn(l + 1, bmp.width); val bt = b.bottom.toInt().coerceIn(t + 1, bmp.height)
        val crop = Bitmap.createBitmap(bmp, l, t, r - l, bt - t)
        val w = (REC_H * crop.width.toFloat() / crop.height).toInt().coerceIn(16, REC_MAX_W)
        val line = Bitmap.createScaledBitmap(crop, w, REC_H, true)
        val px = IntArray(w * REC_H).also { line.getPixels(it, 0, w, 0, 0, w, REC_H) }
        val data = FloatArray(3 * w * REC_H)
        val plane = w * REC_H
        for (i in px.indices) {
            val c = px[i]
            data[i] = ((c and 0xFF) / 255f - 0.5f) / 0.5f                    // B
            data[plane + i] = ((c shr 8 and 0xFF) / 255f - 0.5f) / 0.5f      // G
            data[2 * plane + i] = ((c shr 16 and 0xFF) / 255f - 0.5f) / 0.5f // R
        }
        val session = rec ?: return "" to 0f
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, REC_H.toLong(), w.toLong())).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val seq = (out[0].value as Array<Array<FloatArray>>)[0]
                val steps = seq.size; val classes = seq[0].size
                val flat = FloatArray(steps * classes); for (s in 0 until steps) System.arraycopy(seq[s], 0, flat, s * classes, classes)
                return PaddlePost.ctc(flat, steps, classes, keys)
            }
        }
    }
}
