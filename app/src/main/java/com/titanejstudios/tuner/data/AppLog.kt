package com.titanejstudios.tuner.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small problem log kept on the box (Settings → Problem log, or http://<tv>:8765/log.txt
 * when the phone remote is on). Keeps the last ~200 KB; crashes are written straight away.
 */
object AppLog {
    private const val MAX_BYTES = 200_000L
    private var file: File? = null
    private val stamp = SimpleDateFormat("dd MMM HH:mm:ss", Locale.UK)

    fun init(context: Context) {
        file = File(context.filesDir, "problems.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write("CRASH", "Flicker closed unexpectedly (${thread.name})\n" + stackOf(error))
            } catch (_: Throwable) {
            }
            try {
                SafeMode.onCrash(error)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun info(message: String) = write("INFO", message)
    fun warn(message: String, error: Throwable? = null) =
        write("PROBLEM", if (error == null) message else "$message\n" + stackOf(error, 8))

    @Synchronized
    private fun write(level: String, message: String) {
        Log.i("Tuner", "$level $message")
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) {
                // Keep the newest half.
                val text = f.readText()
                f.writeText(text.substring(text.length / 2).substringAfter('\n'))
            }
            f.appendText("${stamp.format(Date())}  $level  $message\n")
        } catch (_: Exception) {
        }
    }

    /** The last [lines] lines in the order they were written (for copying – crash details read top to bottom). */
    @Synchronized
    fun readInOrder(lines: Int = 400): String = try {
        val f = file
        if (f == null || !f.exists()) "" else f.readLines().takeLast(lines).joinToString("\n")
    } catch (e: Exception) {
        ""
    }

    /** Newest entries first. */
    @Synchronized
    fun read(): String = try {
        val f = file
        if (f == null || !f.exists()) "" else f.readLines().takeLast(600).reversed().joinToString("\n")
    } catch (e: Exception) {
        ""
    }

    @Synchronized
    fun clear() {
        try {
            file?.writeText("")
        } catch (_: Exception) {
        }
    }

    private fun stackOf(error: Throwable, lines: Int = 30): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        return sw.toString().lines().take(lines).joinToString("\n")
    }
}
