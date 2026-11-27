package com.scanpesa.app.automation

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.scanpesa.app.ui.PhotoReader
import org.json.JSONObject
import java.io.File

/**
 * Accuracy benchmark, run from a computer over USB. Reads every image in the app's files/bench
 * folder through exactly the same code as a real scan (PhotoReader + automatic choice) and writes
 * one JSON line per photo to files/bench_results.jsonl.
 *
 *   adb shell am start -n com.scanpesa.app/.automation.AdbBenchActivity
 *
 * Protected by android.permission.DUMP (adb shell / system only).
 */
class AdbBenchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val set = intent.getStringExtra("set") ?: "bench"
        val dir = File(filesDir, set)
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        val out = File(filesDir, "${set}_results.jsonl").apply { writeText("") }
        Log.i(TAG, "benchmark: ${files.size} images")
        next(files, 0, out)
    }

    private fun next(files: List<File>, i: Int, out: File) {
        if (i >= files.size) {
            Log.i(TAG, "benchmark: DONE")
            finish()
            return
        }
        val f = files[i]
        val started = System.currentTimeMillis()
        val bmp = PhotoReader.fromFile(f.absolutePath)
        if (bmp == null) {
            out.appendText(JSONObject().put("file", f.name).put("error", "decode").toString() + "\n")
            next(files, i + 1, out)
            return
        }
        PhotoReader.read(bmp) { scan ->
            val pick = scan?.decision?.pick
            val o = JSONObject()
                .put("file", f.name)
                .put("w", bmp.width).put("h", bmp.height)
                .put("ms", System.currentTimeMillis() - started)
                .put("type", pick?.candidate?.type?.name ?: "")
                .put("number", pick?.candidate?.number ?: "")
                .put("account", pick?.candidate?.account ?: "")
                .put("uncertain", scan?.decision?.uncertain ?: false)
                .put("votes", "${scan?.decision?.votes ?: 0}/${scan?.decision?.readings ?: 0}")
                .put("picks", scan?.picks?.joinToString(" | ") { it?.let { p -> "${p.candidate.type}:${p.candidate.number}:${p.candidate.account.orEmpty()}" } ?: "-" } ?: "")
                .put("candidates", scan?.candidates?.joinToString(";") { "${it.type}:${it.number}:${it.account.orEmpty()}" } ?: "")
                .put("text", scan?.text ?: "")
            out.appendText(o.toString() + "\n")
            next(files, i + 1, out)
        }
    }

    companion object { const val TAG = "ScanPesaBench" }
}
