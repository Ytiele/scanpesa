package com.scanpesa.app.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

/**
 * ScanPesa's "eyes": takes a screenshot through the accessibility service and reads it with the
 * on-device ML Kit text recogniser. Used when an app (e.g. Samsung's SIM Toolkit) doesn't show
 * its menu in the accessibility tree. Screenshots stay in memory and are discarded immediately.
 */
class Eyes(private val service: AccessibilityService, private val excludeArea: () -> Rect?) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false
    @Volatile private var lastRequestAt = 0L

    /** Latest reading: lines and when the screenshot was taken. */
    @Volatile var lines: List<SeenLine> = emptyList()
        private set
    @Volatile var takenAt = 0L
        private set
    @Volatile var lastError: String? = null
        private set

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Takes a new screenshot unless one is in progress or one was taken < [minGapMs] ago. */
    fun look(minGapMs: Long = 700) {
        if (!supported || busy) return
        val now = System.currentTimeMillis()
        if (now - lastRequestAt < minGapMs) return
        lastRequestAt = now
        busy = true
        capture(now)
    }

    fun forget() { lines = emptyList(); takenAt = 0 }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun capture(requestedAt: Long) {
        service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                val bitmap = try {
                    val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                    hw?.copy(Bitmap.Config.ARGB_8888, false).also { hw?.recycle() }
                } finally {
                    shot.hardwareBuffer.close()
                }
                if (bitmap == null) { lastError = "screenshot empty"; busy = false; return }
                val skip = excludeArea()
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { text ->
                        lines = text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                            val b = l.boundingBox ?: return@mapNotNull null
                            if (skip != null && Rect.intersects(skip, b)) return@mapNotNull null
                            SeenLine(l.text, b.centerX(), b.centerY())
                        }
                        takenAt = requestedAt
                        lastError = null
                    }
                    .addOnFailureListener { lastError = "OCR failed: ${it.message}" }
                    .addOnCompleteListener { bitmap.recycle(); busy = false }
            }

            override fun onFailure(errorCode: Int) {
                // 3 = interval too short; 1 = internal error; 4 = invalid display; 2 = no permission
                lastError = "screenshot failed (code $errorCode)"
                busy = false
            }
        })
    }

    fun close() {
        recognizer.close()
        executor.shutdown()
    }
}
