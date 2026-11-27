package com.scanpesa.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.scanpesa.app.model.Candidate
import com.scanpesa.app.parser.Box
import com.scanpesa.app.parser.Consensus
import com.scanpesa.app.parser.Glyph
import com.scanpesa.app.parser.Glyphs
import com.scanpesa.app.parser.Layout
import com.scanpesa.app.parser.MpesaParser
import com.scanpesa.app.parser.NumberToken
import com.scanpesa.app.parser.OcrLine
import com.scanpesa.app.parser.OcrWord
import com.scanpesa.app.parser.PhotoTokens

/** A photo that has been read: the picture, every tappable number on it, and the parsed payments. */
class PhotoScan(
    val bitmap: Bitmap,
    val text: String,
    val tokens: List<NumberToken>,
    val candidates: List<Candidate>,
    /** What all the readings agreed on (or "not sure"). */
    val decision: Consensus.Decision,
)

/** Reads payment numbers from a full photo (camera shot or gallery image), on-device. */
object PhotoReader {
    /** Long side the photo is scaled to before OCR: sharp enough for signs across a room, fast to read. */
    private const val OCR_SIDE = 2560

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Small photos are enlarged to at least this long side: the reader needs ~20 px tall characters. */
    private const val MIN_SIDE = 1600

    private class Reading(val lines: List<OcrLine>, val text: String)

    /**
     * Reads a photo several ways and lets the readings vote:
     *  - 3 versions of the image: as-is, high-contrast greyscale, inverted (light text on dark signs);
     *  - 2 reading orders each: the engine's own lines, and lines rebuilt from word positions.
     * The number most readings agree on wins; disagreement means "not sure" (retake), never a guess.
     */
    fun read(source: Bitmap, onDone: (PhotoScan?) -> Unit) {
        val base = enlarge(source)
        val variants = listOf(base, contrast(base, invert = false), contrast(base, invert = true))
        val readings = mutableListOf<Reading>()
        fun step(i: Int) {
            if (i >= variants.size) { finish(base, readings, onDone); return }
            recognizer.process(InputImage.fromBitmap(variants[i], 0))
                .addOnSuccessListener { r ->
                    val engineLines = toLines(r)
                    readings += Reading(engineLines, engineLines.joinToString("\n") { l -> l.words.joinToString(" ") { it.text } })
                    val rows = Layout.rows(engineLines.flatMap { it.words })
                    readings += Reading(rows, Layout.text(rows))
                }
                .addOnCompleteListener { step(i + 1) }
        }
        step(0)
    }

    /** Engine result -> lines of words, with box edges ("|" taller than the digits) removed. */
    private fun toLines(r: com.google.mlkit.vision.text.Text): List<OcrLine> = r.textBlocks.flatMap { it.lines }.map { l ->
        val lineGlyphs = l.elements.flatMap { e -> e.symbols.mapNotNull { sy -> sy.boundingBox?.let { Glyph(sy.text, it.toBox()) } } }
        val digitH = Glyphs.digitHeight(lineGlyphs)
        OcrLine(l.elements.mapNotNull { e ->
            val b = e.boundingBox ?: return@mapNotNull null
            val glyphs = e.symbols.mapNotNull { sy -> sy.boundingBox?.let { Glyph(sy.text, it.toBox()) } }
            val text = if (glyphs.isEmpty()) e.text else Glyphs.word(glyphs, digitH)
            if (text.isBlank()) null else OcrWord(text, b.toBox())
        })
    }

    private fun finish(base: Bitmap, readings: List<Reading>, onDone: (PhotoScan?) -> Unit) {
        if (readings.isEmpty()) { onDone(null); return }
        val picks = readings.map { rd ->
            val c = MpesaParser.parse(rd.text)
            PhotoTokens.autoPick(PhotoTokens.extract(rd.lines, c), c, rd.text)
        }
        val first = readings[0]
        val firstCandidates = MpesaParser.parse(first.text)
        onDone(
            PhotoScan(
                base, readings.joinToString("\n---\n") { it.text },
                PhotoTokens.extract(first.lines, firstCandidates), firstCandidates,
                Consensus.decide(picks),
            ),
        )
    }

    /** Enlarges small images (web pictures, low-end cameras) so characters are big enough to read. */
    private fun enlarge(b: Bitmap): Bitmap {
        val long = maxOf(b.width, b.height)
        if (long >= MIN_SIDE) return b
        val s = MIN_SIDE.toFloat() / long
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }

    /** Greyscale with the darkest 2% / brightest 2% stretched to black / white (optionally inverted). */
    private fun contrast(b: Bitmap, invert: Boolean): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val hist = IntArray(256)
        val lum = IntArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            val y = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            lum[i] = y; hist[y]++
        }
        fun percentile(p: Double): Int {
            var acc = 0; val target = (px.size * p).toInt()
            for (v in 0..255) { acc += hist[v]; if (acc >= target) return v }
            return 255
        }
        val lo = percentile(0.02); val hi = maxOf(lo + 1, percentile(0.98))
        for (i in px.indices) {
            var v = ((lum[i] - lo) * 255 / (hi - lo)).coerceIn(0, 255)
            if (invert) v = 255 - v
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Camera capture → upright bitmap scaled to [OCR_SIDE]. */
    fun fromCapture(image: ImageProxy): Bitmap {
        val full = image.toBitmap()
        val rot = image.imageInfo.rotationDegrees
        val scale = minOf(1f, OCR_SIDE.toFloat() / maxOf(full.width, full.height))
        val m = Matrix().apply { postScale(scale, scale); postRotate(rot.toFloat()) }
        val out = Bitmap.createBitmap(full, 0, 0, full.width, full.height, m, true)
        if (out !== full) full.recycle()
        return out
    }

    /** Uploaded image (gallery / files) → upright software bitmap scaled to [OCR_SIDE]. */
    fun fromUri(ctx: Context, uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = minOf(1f, OCR_SIDE.toFloat() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
            }
        } else {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }?.let(::fit)
        }
    }.getOrNull()

    /** Image file on the device (used by the USB benchmark). */
    fun fromFile(path: String): Bitmap? = BitmapFactory.decodeFile(path)?.let(::fit)

    private fun fit(b: Bitmap): Bitmap {
        val s = minOf(1f, OCR_SIDE.toFloat() / maxOf(b.width, b.height))
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }

    private fun android.graphics.Rect.toBox() = Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}
