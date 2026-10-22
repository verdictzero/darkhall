package com.verdictzero.darkhall.app

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort crash capture. Darkhall users are often somewhere without adb (a
 * boat, a beach), so an uncaught exception is written to the app's private storage
 * and shown, with a copy button, on the next launch.
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last-crash.txt")

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val rt = Runtime.getRuntime()
                val text = buildString {
                    append("Darkhall crash ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
                    append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                        .append(" / Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                    append("Heap: used ").append((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).append(" MB, max ")
                        .append(rt.maxMemory() / (1024 * 1024)).append(" MB\n")
                    append("Thread: ").append(thread.name).append("\n\n")
                    append(sw.toString())
                }
                file(app).writeText(text)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(context: Context): String? {
        val f = file(context)
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    fun clear(context: Context) {
        file(context).delete()
    }
}

class DarkhallApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
