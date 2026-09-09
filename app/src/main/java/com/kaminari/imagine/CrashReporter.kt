package com.kaminari.imagine

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global crash logger: writes every uncaught exception to
 * /data/data/com.kaminari.imagine/files/crash.log (plus logcat),
 * then rethrows to the default handler so the system crash dialog still works.
 */
object CrashReporter {

    private const val TAG = "ImagineCrash"
    private const val FILE_NAME = "crash.log"
    private const val MAX_LOG_BYTES = 2L * 1024 * 1024 // keep the file bounded

    fun install(context: Context): CrashReporter {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(appContext, thread, throwable)
            } catch (ignored: Throwable) {
                // never let the crash handler itself crash
            }
            // chain to the previous handler (system default shows the dialog / restarts)
            previous?.uncaughtException(thread, throwable)
        }
        return this
    }

    /** Append a non-fatal record (useful for captured engine errors). */
    fun log(context: Context, tag: String, message: String, throwable: Throwable? = null) {
        try {
            val file = crashFile(context)
            file.parentFile?.mkdirs()
            val trace = throwable?.let {
                val sw = StringWriter()
                it.printStackTrace(PrintWriter(sw))
                "\n${sw}"
            } ?: ""
            file.appendText("\n[${now()}] [$tag] $message$trace\n")
            trimIfNeeded(file)
        } catch (ignored: Throwable) {
        }
    }

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val header = buildString {
            append("\n════════ CRASH ════════\n")
            append("time:     ${now()}\n")
            append("thread:   ${thread.name}\n")
            append("device:   ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})\n")
            append("app:      ${context.packageName}\n")
            append("message:  ${throwable.message}\n")
            append("stacktrace:\n")
            append(sw)
            append("════════════════════════\n")
        }
        val file = crashFile(context)
        file.parentFile?.mkdirs()
        file.appendText(header)
        trimIfNeeded(file)
        Log.e(TAG, "Crash written to ${file.absolutePath}\n$header")
    }

    private fun crashFile(context: Context): File =
        File(context.filesDir, FILE_NAME)

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun trimIfNeeded(file: File) {
        if (file.length() > MAX_LOG_BYTES) {
            // keep only the tail (latest records)
            val keep = file.readText().takeLast((MAX_LOG_BYTES / 2).toInt())
            file.writeText(keep)
        }
    }
}
