package com.scanpesa.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
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

    /** Gallery image → upright software bitmap scaled to [OCR_SIDE] (EXIF rotation applied on Android 9+). */
    fun fromUri(ctx: Context, uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = minOf(1f, OCR_SIDE.toFloat() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= OCR_SIDE) sample *= 2
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }.getOrNull()
}
