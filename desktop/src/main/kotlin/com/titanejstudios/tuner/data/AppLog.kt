package com.titanejstudios.tuner.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The PC version's problem log: %USERPROFILE%\.tuner\flicker.log (the newest 2 MB). */
object AppLog {
    val file: File = File(System.getProperty("user.home"), ".tuner").apply { mkdirs() }.let { File(it, "flicker.log") }
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK)

    @Synchronized
    private fun write(line: String) {
        runCatching {
            if (file.length() > 2_000_000L) file.writeText("")
            file.appendText(stamp.format(Date()) + "  " + line + "\n")
        }
    }

    fun info(message: String) = write("INFO  $message")

    fun warn(message: String, error: Throwable? = null) {
        write("WARN  $message" + (error?.let { ": " + it.stackTraceToString() } ?: ""))
    }
}
