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
