package com.example.synergic_pos_offline.utils

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small on-device log for the print path, written to a file rather than only
 * logcat. A till in the field usually has no adb access, so this is how a print
 * failure gets diagnosed: Printer Settings has a "View print log" screen showing
 * this file's content as selectable text, which can be copied out and sent
 * without ever connecting the device to a computer.
 *
 * ## What a usable print log has to answer
 *
 * "It printed the first time and not the second" is not something anyone can act on,
 * and a log of bare sentences does not improve on it. Three things turn the same
 * report into a diagnosis, and all three are built in here rather than left to each
 * caller to remember:
 *
 * - **Which attempt.** Every print opens a [Job] with a number, and every line of that
 *   print carries it. The first print and the second are then two blocks in the file
 *   that can be read side by side, instead of a stream of lines that all look alike.
 * - **How long each step took.** A step stamped `+2014ms` is a socket that waited out
 *   its connect timeout; the same step at `+3ms` is a printer that refused outright.
 *   The two have different causes and the message on its own cannot tell them apart,
 *   so [Job.step] prints the elapsed time since the job started and since the previous
 *   step, always.
 * - **Which thread.** The label path hands work between the UI thread, a print worker
 *   and the main looper, and a crash like "Can't create handler inside thread
 *   Thread[pool-8-thread-1]" is only intelligible if the log says where each step ran.
 *   Every line carries its thread name.
 *
 * Failures also carry the stack trace ([Job.failed]), not just `e.message` - half the
 * exceptions worth reading here have a null message.
 */
object PrintLog {
    private const val FILE_NAME = "print_log.txt"

    /**
     * Trimmed on write once past this, so the file never grows unbounded - and trimmed
     * back to [TRIM_TO_CHARS] rather than to the limit, so a busy counter is not
     * rewriting the whole file on every line once it fills up.
     *
     * The old cap was 20,000 characters, which a single afternoon's printing now fills
     * several times over. A log that has already discarded the failure being chased is
     * not worth keeping at all, and this is a text file on a till with gigabytes free.
     */
    private const val MAX_FILE_CHARS = 200_000
    private const val TRIM_TO_CHARS = 150_000

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Numbers the jobs within this run of the app - see [Job]. */
    private val jobCounter = AtomicInteger(0)

    /** Whether this process has already announced itself in the file. */
    private var headerWritten = false

    /**
     * One print, from the tap that asked for it to the result that came back.
     *
     * Handed down the call chain rather than created per layer, so the fragment's
     * "operator asked for 4 labels", the transport's "openport attempt 2/3" and the
     * "job finished" line all carry the same number and share one clock. Steps are
     * indented under the job's own START/END lines so a block can be picked out of the
     * file at a glance.
     */
    class Job internal constructor(
        private val context: Context,
        private val tag: String,
        private val number: Int,
        private val what: String
    ) {
        private val startedAt = SystemClock.elapsedRealtime()
        private var lastStepAt = startedAt

        init {
            write(context, tag, "===== #$number START  $what")
        }

        /** One step of the job, stamped with the time since the start and since the last step. */
        fun step(message: String) {
            val now = SystemClock.elapsedRealtime()
            val sinceStart = now - startedAt
            val sinceLast = now - lastStepAt
            lastStepAt = now
            write(context, tag, "  #$number  +${sinceStart}ms (+${sinceLast}ms)  $message")
        }

        /** A step that threw. The stack trace goes in the file - the message alone is often null. */
        fun failed(message: String, error: Throwable) {
            val now = SystemClock.elapsedRealtime()
            lastStepAt = now
            write(
                context, tag,
                "  #$number  +${now - startedAt}ms  !! $message :: " +
                    "${error.javaClass.name}: ${error.message}\n${stackOf(error)}",
                isError = true
            )
        }

        /** The last line of the block. [outcome] is what the operator was told. */
        fun done(outcome: String) {
            write(
                context, tag,
                "===== #$number END after ${SystemClock.elapsedRealtime() - startedAt}ms  $outcome"
            )
        }

        /** Multi-line detail - the TSPL actually sent, a config dump - kept out of the step lines. */
        fun detail(title: String, body: String) {
            write(context, tag, "  #$number  $title:\n${body.prependIndent("      | ")}")
        }
    }

    /** Opens a numbered [Job]. Every print should start with one. */
    fun job(context: Context, tag: String, what: String): Job =
        Job(context.applicationContext, tag, jobCounter.incrementAndGet(), what)

    @Synchronized
    fun d(context: Context, tag: String, message: String) = write(context, tag, message)

    /** As [d], but the stack trace is kept too. */
    @Synchronized
    fun e(context: Context, tag: String, message: String, error: Throwable) =
        write(
            context, tag,
            "!! $message :: ${error.javaClass.name}: ${error.message}\n${stackOf(error)}",
            isError = true
        )

    /** The full log content, oldest first, for the viewer screen. */
    fun read(context: Context): String {
        val file = logFile(context)
        if (!file.exists()) return "(no print activity logged yet - try a print, then come back here)"
        return runCatching { file.readText() }.getOrDefault("(could not read the log)")
    }

    fun clear(context: Context) {
        runCatching { logFile(context).delete() }
        synchronized(this) { headerWritten = false }
    }

    @Synchronized
    private fun write(context: Context, tag: String, message: String, isError: Boolean = false) {
        if (isError) Log.e(tag, message) else Log.d(tag, message)
        runCatching {
            val file = logFile(context)
            writeHeaderOnce(context, file)
            // Appended rather than read-modify-written. The previous version read the
            // whole file back and rewrote it for every single line, which is fine for a
            // handful of lines a day and quadratic once the path logs properly.
            file.appendText(
                "${timeFormat.format(Date())} (${Thread.currentThread().name}) [$tag] $message\n"
            )
            if (file.length() > MAX_FILE_CHARS) {
                val kept = file.readText().takeLast(TRIM_TO_CHARS)
                file.writeText("(earlier entries trimmed)\n" + kept.substringAfter('\n'))
            }
        }
    }

    /**
     * One banner per run of the app, naming the build and the device.
     *
     * A log arriving from a counter is worth little without it: "which version is this
     * till on" and "which Android is it" are the first two questions any print problem
     * raises, and neither can be recovered from the lines themselves.
     */
    private fun writeHeaderOnce(context: Context, file: File) {
        if (headerWritten) return
        headerWritten = true
        val app = runCatching {
            val pm = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pm.versionName} (${@Suppress("DEPRECATION") pm.versionCode})"
        }.getOrDefault("unknown build")
        file.appendText(
            "\n======================================================================\n" +
                "  app started ${dateFormat.format(Date())}\n" +
                "  build $app\n" +
                "  ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT})\n" +
                "======================================================================\n"
        )
    }

    private fun stackOf(error: Throwable): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return writer.toString().trimEnd().prependIndent("      | ")
    }

    private fun logFile(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)
}
