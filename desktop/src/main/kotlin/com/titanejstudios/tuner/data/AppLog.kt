package com.titanejstudios.tuner.data

/** The PC version's problem log (the TV one writes to a file on the TV). */
object AppLog {
    fun info(message: String) = println("INFO  $message")
    fun warn(message: String, error: Throwable? = null) = System.err.println("WARN  $message" + (error?.let { ": ${it.message}" } ?: ""))
}
