package com.scanpesa.app.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.scanpesa.app.model.Candidate
import com.scanpesa.app.parser.Box
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
)

/** Reads payment numbers from a full photo (camera shot or gallery image), on-device. */
object PhotoReader {
    /** Long side the photo is scaled to before OCR: sharp enough for signs across a room, fast to read. */
    private const val OCR_SIDE = 2560

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun read(bitmap: Bitmap, onDone: (PhotoScan?) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { r ->
                val lines = r.textBlocks.flatMap { it.lines }.map { l ->
                    OcrLine(l.elements.mapNotNull { e ->
                        val b = e.boundingBox ?: return@mapNotNull null
                        OcrWord(e.text, Box(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()))
                    })
                }
                val candidates = MpesaParser.parse(r.text)
                onDone(PhotoScan(bitmap, r.text, PhotoTokens.extract(lines, candidates), candidates))
            }
            .addOnFailureListener { onDone(null) }
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
}
