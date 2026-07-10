package org.fivesevenfive.wearvian.util

import android.content.Context
import org.fivesevenfive.wearvian.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent crash recorder. Background crashes are hard to debug after the fact: the
 * OS dropbox and logcat ring buffers rotate within hours, adb is rarely attached at the
 * moment of the crash, and a watch bug report only helps if you happen to capture one in
 * time. This installs a default uncaught-exception handler that writes the full stack
 * trace to a file in the app's private storage BEFORE chaining to the system handler, so
 * the trace survives process death, reboots, and a missing adb tether — pull it later
 * from `/data/data/org.fivesevenfive.wearvian/files/crashes/` with `adb [root] cat`.
 *
 * Runs in every process the app spawns (installed from [android.app.Application.onCreate]).
 * The handler itself must never throw and must always chain, or it would mask the very
 * crash it exists to record.
 */
object CrashLog {
    private const val DIR = "crashes"
    private const val KEEP = 5
    private val ts = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss.SSS", Locale.US)

    fun install(context: Context) {
        // Debugging builds only (debug + debugRelease). The production Play build keeps the
        // platform's own crash handling and writes no crash files to disk.
        if (BuildConfig.PRODUCTION) return
        val dir = File(context.filesDir, DIR)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(dir, thread, throwable)
            } catch (_: Throwable) {
                // Never let crash-logging mask the original crash.
            }
            // Chain to the platform handler so dropbox capture + process kill still happen.
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(dir: File, thread: Thread, throwable: Throwable) {
        dir.mkdirs()
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val now = Date()
        File(dir, "crash-${ts.format(now)}.log").writeText(
            buildString {
                append("wearvian crash\n")
                append("time:    ").append(now).append('\n')
                append("thread:  ").append(thread.name).append(" (id=").append(thread.id).append(")\n")
                append("build:   ").append(BuildConfig.VERSION_NAME)
                    .append(" (").append(BuildConfig.VERSION_CODE).append(")\n\n")
                append(stack)
            },
        )
        // Keep only the most recent KEEP crash files.
        dir.listFiles { f -> f.name.startsWith("crash-") }
            ?.sortedByDescending { it.name }
            ?.drop(KEEP)
            ?.forEach { it.delete() }
    }

    /** Newest-first crash reports currently on disk (for surfacing on the debug screen). */
    fun reports(context: Context): List<File> =
        File(context.filesDir, DIR).listFiles { f -> f.name.startsWith("crash-") }
            ?.sortedByDescending { it.name }
            ?: emptyList()
}
