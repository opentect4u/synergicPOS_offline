package com.example.synergic_pos_offline.utils

import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R

/**
 * Runs work that reads or writes the whole database behind a dialog that cannot be
 * dismissed.
 *
 * A backup or a restore is not main-thread work on a real shop's data: reading a
 * year of bills on it freezes the till for as long as it takes and Android offers to
 * close the app, which an operator reports as a crash. The screen is blocked rather
 * than left usable because there is nothing useful to do on it meanwhile.
 *
 * Anything the work throws is caught and shown, rather than reaching the operator as
 * a closed app.
 */
object BusyDialog {

    /** Runs [work] on a worker thread while [message] is shown over [fragment]. */
    fun run(fragment: Fragment, message: String, work: () -> Unit) {
        val context = fragment.context ?: return
        val progress = dialog(fragment, message) ?: return
        Thread {
            var failure: Exception? = null
            try {
                work()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Background work failed", e)
                failure = e
            }
            onMain(fragment) {
                runCatching { progress.dismiss() }
                failure?.let {
                    DialogUtils.showSuccess(
                        context = context,
                        title = "That did not finish",
                        message = it.message ?: it.javaClass.simpleName
                    )
                }
            }
        }.start()
    }

    /**
     * What a long piece of work tells its dialog as it goes. Safe to call from the
     * worker thread; each call is handed to the main thread to draw.
     */
    interface Progress {
        /** 0-100 for a measured step, or -1 for one that cannot say how far it is. */
        fun percent(value: Int)

        /** Replaces the line above the bar - "Backing up…", "Restoring…". */
        fun status(text: String)
    }

    /**
     * [run], with a bar and a percentage instead of a bare spinner.
     *
     * For a restore: a whole shop's year is minutes of work on a 3 GB till, and a
     * spinner that turns for that long with nothing else changing reads as hung -
     * which is when an operator pulls the plug on a restore half way through.
     */
    fun runWithProgress(fragment: Fragment, message: String, work: (Progress) -> Unit) {
        val context = fragment.context ?: return
        val view = ProgressView(fragment, message) ?: return
        Thread {
            var failure: Exception? = null
            try {
                work(view)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Background work failed", e)
                failure = e
            }
            onMain(fragment) {
                runCatching { view.dialog.dismiss() }
                failure?.let {
                    DialogUtils.showSuccess(
                        context = context,
                        title = "That did not finish",
                        message = it.message ?: it.javaClass.simpleName
                    )
                }
            }
        }.start()
    }

    /** The dialog behind [runWithProgress]: a line of text, a bar and its percentage. */
    private class ProgressView private constructor(
        private val fragment: Fragment,
        val dialog: AlertDialog,
        private val label: TextView,
        private val bar: ProgressBar,
        private val figure: TextView
    ) : Progress {

        /** When the measured step began - its first figure - for the time estimate. */
        @Volatile private var startedAt = 0L

        override fun percent(value: Int) {
            // Timed here, on the worker, as the figure arrives - not when the main
            // thread gets round to drawing it.
            val now = android.os.SystemClock.elapsedRealtime()
            if (value >= 0 && startedAt == 0L) startedAt = now
            val left = if (value in 1..99) remaining(now - startedAt, value) else null
            BusyDialog.onMain(fragment) {
                if (value < 0) {
                    bar.isIndeterminate = true
                    figure.text = ""
                } else {
                    bar.isIndeterminate = false
                    bar.progress = value.coerceIn(0, 100)
                    // The read is done at 100, but the rows are not saved until the
                    // transaction commits - so it says so, rather than sitting at 100%
                    // looking finished while the till is still busy.
                    figure.text = when {
                        value >= 100 -> "100%  ·  finishing up…"
                        left != null -> "$value%  ·  $left"
                        else -> "$value%  ·  working out time left…"
                    }
                }
            }
        }

        /**
         * "about 4 min left", from how long the first [percent] took.
         *
         * Not shown for the first few seconds or the first two percent: the file opens
         * with the settings and masters, which go in at a different pace from the bills
         * behind them, and an estimate made from those swings by minutes. Rounded
         * coarsely on purpose - a figure that ticks down second by second on a
         * fluctuating rate reads as wrong every time it jumps back up.
         */
        private fun remaining(elapsedMs: Long, percent: Int): String? {
            if (percent < 2 || elapsedMs < 3_000) return null
            val leftSec = (elapsedMs * (100 - percent) / percent) / 1000
            return when {
                leftSec < 10 -> "a few seconds left"
                leftSec < 60 -> "about ${((leftSec + 9) / 10) * 10} sec left"
                leftSec < 90 -> "about 1 min left"
                leftSec < 3600 -> "about ${(leftSec + 30) / 60} min left"
                else -> {
                    val h = leftSec / 3600
                    val m = (leftSec % 3600 + 30) / 60
                    if (m == 0L) "about $h hr left" else "about $h hr $m min left"
                }
            }
        }

        override fun status(text: String) = BusyDialog.onMain(fragment) { label.text = text }

        companion object {
            operator fun invoke(fragment: Fragment, message: String): ProgressView? {
                val context = fragment.context ?: return null
                val density = context.resources.displayMetrics.density
                fun dp(value: Int) = (value * density).toInt()
                val textColor = context.resources.getColor(R.color.text_main, null)

                val label = TextView(context).apply {
                    text = message
                    textSize = 15f
                    setTextColor(textColor)
                }
                val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    isIndeterminate = true
                    progressTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getThemeColor(context))
                    indeterminateTintList = progressTintList
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(16) }
                }
                val figure = TextView(context).apply {
                    textSize = 14f
                    setTextColor(textColor)
                    gravity = Gravity.END
                    setPadding(0, dp(8), 0, 0)
                }
                val column = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(24), dp(24), dp(24), dp(20))
                    minimumWidth = dp(320)
                    addView(label)
                    addView(bar)
                    addView(figure)
                }
                val dialog = AlertDialog.Builder(context)
                    .setView(column)
                    .setCancelable(false)
                    .create()
                    .also { it.show() }
                return ProgressView(fragment, dialog, label, bar, figure)
            }
        }
    }

    /** Runs [block] on the main thread, and only while the screen is still there. */
    fun onMain(fragment: Fragment, block: () -> Unit) {
        fragment.activity?.runOnUiThread { if (fragment.isAdded) block() }
    }

    /** A spinner and a line of text, shown while the database is being read or written. */
    private fun dialog(fragment: Fragment, message: String): AlertDialog? {
        val context = fragment.context ?: return null
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(ProgressBar(context))
            addView(TextView(context).apply {
                text = message
                textSize = 15f
                setPadding(dp(16), 0, 0, 0)
                setTextColor(resources.getColor(R.color.text_main, null))
            })
        }
        return AlertDialog.Builder(context)
            .setView(row)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private const val TAG = "BusyDialog"
}
