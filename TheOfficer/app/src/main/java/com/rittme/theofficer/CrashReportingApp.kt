package com.rittme.theofficer

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashReportingApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(throwable)
            } catch (e: Exception) {
                Log.e("CrashReportingApp", "Failed to write crash log", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun writeCrashLog(throwable: Throwable) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val timestamp = dateFormat.format(Date())
        val deviceInfo = "Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})"
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val crashText = buildString {
            appendLine("Crash time: $timestamp")
            appendLine(deviceInfo)
            appendLine()
            appendLine(sw.toString())
        }

        val crashFile = File(filesDir, CRASH_FILE_NAME)
        crashFile.writeText(crashText)
    }

    companion object {
        const val CRASH_FILE_NAME = "last_crash.txt"
    }
}
