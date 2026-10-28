package com.qmxz.pilotbotv2.diagnostics

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash recorder for the "连续 1 小时不闪退" acceptance test.
 *
 * Installs a default uncaught-exception handler that appends a short report
 * (time, device, thread, stack trace) to a private file, then CHAINS to the
 * previous handler so the system crash behaviour is unchanged — this logger
 * never swallows a crash and never keeps a dying process alive.
 *
 * The file is private to the app. A future "分享崩溃日志" button can read it
 * via [readRecent]; v2 does not upload anything automatically.
 */
object CrashLog {

    private const val FILE_NAME = "crash.log"
    private const val MAX_BYTES = 200 * 1024L

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                appendCrash(appContext, thread, error)
            } catch (_: Exception) {
                // Logging must never interfere with crash handling.
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                System.exit(10)
            }
        }
    }

    private fun appendCrash(context: Context, thread: Thread, error: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date())
        val entry = StringBuilder()
            .append("=== $timestamp ===\n")
            .append("设备：${Build.MANUFACTURER} ${Build.MODEL} SDK ${Build.VERSION.SDK_INT}\n")
            .append("线程：${thread.name}\n")
            .append(Log.getStackTraceString(error))
            .append("\n")
            .toString()
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists() && file.length() > MAX_BYTES) {
            file.writeText(entry)
        } else {
            file.appendText(entry)
        }
    }

    /** Most recent crash text (tail), or a "no records" message. */
    fun readRecent(context: Context, maxChars: Int = 8000): String {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) {
                "暂无崩溃记录"
            } else {
                file.readText().takeLast(maxChars)
            }
        } catch (_: Exception) {
            "崩溃日志读取失败"
        }
    }

    fun clear(context: Context) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.delete()
        } catch (_: Exception) {
        }
    }
}
