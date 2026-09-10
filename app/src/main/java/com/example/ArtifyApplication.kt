package com.example

import android.app.Application
import java.io.File

/**
 * Installs a process-wide crash handler for internal testing builds. Sideloaded debug APKs
 * have no Play Console crash reporting, and the OEM's own crash dialog on many devices shows
 * only a generic message with no technical detail. Starting a new Activity from inside an
 * uncaught-exception handler is unreliable on some OEM skins (the process may already be
 * mid-teardown), so instead this writes the crash details to a file synchronously — which
 * cannot fail the way starting an Activity can — then lets the platform's default handler run
 * as normal. MainActivity checks for that file on the next launch and shows it full-screen.
 */
class ArtifyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize WorkManager background sync to guarantee offline attendance reconciliation
        try {
            com.example.data.sync.AttendanceSyncWorker.schedulePeriodicWork(this)
        } catch (e: Exception) {
            android.util.Log.w("ArtifyApplication", "Could not initialize WorkManager sync: ${e.message}")
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val details = buildString {
                    appendLine(throwable::class.java.name)
                    appendLine(throwable.message ?: "(no message)")
                    appendLine()
                    appendLine(throwable.stackTraceToString())
                    var cause = throwable.cause
                    while (cause != null) {
                        appendLine()
                        appendLine("Caused by: ${cause::class.java.name}: ${cause.message}")
                        appendLine(cause.stackTraceToString())
                        cause = cause.cause
                    }
                }
                File(filesDir, CRASH_FILE_NAME).writeText(details)
            } catch (_: Throwable) {
                // Best effort only — never let the crash reporter itself throw.
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CRASH_FILE_NAME = "last_crash.txt"
    }
}
