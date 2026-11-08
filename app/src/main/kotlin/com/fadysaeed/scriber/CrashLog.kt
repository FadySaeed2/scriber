package com.fadysaeed.scriber

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the details of the last time Scriber stopped unexpectedly, on this phone only, so Fady can
 * copy them and send them for a fix. Covers Kotlin crashes (caught here) and crashes inside the
 * speech engines, out-of-memory kills and freezes (reported by Android on the next start).
 */
object CrashLog {
    private fun dir(c: Context) = File(c.noBackupFilesDir, "crash").apply { mkdirs() }
    private fun last(c: Context) = File(dir(c), "last.txt")
    private fun seen(c: Context) = File(dir(c), "seen.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                last(app).writeText("${stamp(System.currentTimeMillis())}, app ${BuildConfig.VERSION_NAME}, thread ${thread.name}\n${error.stackTraceToString()}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Text of everything not yet cleared, or null when Scriber has not stopped unexpectedly. */
    fun report(context: Context): String? {
        val seenAt = runCatching { seen(context).readText().trim().toLong() }.getOrDefault(0L)
        val parts = mutableListOf<String>()
        val file = last(context)
        if (file.isFile && file.lastModified() > seenAt) parts += file.readText()
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            for (e in am.getHistoricalProcessExitReasons(context.packageName, 0, 6)) {
                if (e.timestamp <= seenAt) continue
                val reason = when (e.reason) {
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash inside native code (speech engine or media decoder)"
                    ApplicationExitInfo.REASON_CRASH -> "crash"
                    ApplicationExitInfo.REASON_ANR -> "froze (not responding)"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "closed by Android for memory"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "closed by Android for using too much"
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
                    else -> continue
                }
                val trace = if (e.reason == ApplicationExitInfo.REASON_ANR) {
                    runCatching { e.traceInputStream?.bufferedReader()?.useLines { it.take(80).joinToString("\n") } }.getOrNull()
                } else null
                parts += buildString {
                    append("${stamp(e.timestamp)}: $reason")
                    e.description?.let { append(" ($it)") }
                    append(", process ${e.processName}, memory ${e.pss / 1024} MB")
                    trace?.let { append("\n").append(it) }
                }
            }
        }
        return if (parts.isEmpty()) null else "Scriber ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE}, ${Build.MODEL}\n\n" + parts.joinToString("\n\n")
    }

    fun clear(context: Context) {
        runCatching { seen(context).writeText(System.currentTimeMillis().toString()) }
    }

    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(ms))
}
