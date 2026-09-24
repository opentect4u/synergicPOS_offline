package com.example.synergic_pos_offline

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.os.Process
import android.util.Log
import com.example.synergic_pos_offline.utils.PrintLog

/**
 * Installs a crash guard over the till's own uncaught-exception handler.
 *
 * ## Why this exists
 *
 * This app talks to three vendor SDKs it does not control - ESC/POS for the
 * receipt printer, TSC's own for the label printer, usb-serial for the weighing
 * scale - each of which runs its own internal thread for I/O or status polling.
 * A printer whose paper roller is not locked properly is a printer answering a
 * write with a fault, and every one of these SDKs has, on the bench, thrown
 * that fault as a plain uncaught exception on ITS OWN thread rather than
 * handing it back as a return value [ThermalPrinter] could catch. Android's
 * default handler answers any uncaught exception, on any thread, by killing
 * the whole process - so a paper cover left open took down the till, not just
 * the print.
 *
 * ## What this does differently
 *
 * A background thread's own crash is let go: that ONE thread stops, the fault
 * is logged, and every other thread - the UI, the till, whatever the operator
 * was doing - carries on exactly as it was. The operator sees a failed print
 * (or, if the failure raced past [ThermalPrinter]'s own `runCatching`, no
 * feedback at all beyond the print not coming out) rather than a closed app.
 *
 * The main thread is not given the same pass - a crash there can leave a
 * half-built view or a Binder call stuck mid-flight, and pretending nothing
 * happened is how THAT turns into a worse, harder-to-explain failure a screen
 * later. It restarts the app cleanly instead, which is what the till's own
 * crash would have forced anyway, minus the vendor SDK ever calling
 * `Process.killProcess` while the wrong thread is blamed for it.
 */
class SynergicPosApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                Log.e(TAG, "Uncaught exception on '${thread.name}'", throwable)
                PrintLog.d(
                    this, TAG,
                    "UNCAUGHT on '${thread.name}': ${throwable.javaClass.simpleName}: " +
                        throwable.message
                )
            }

            if (thread === Looper.getMainLooper().thread) {
                // The one crash this cannot safely shrug off. Restarted rather than
                // left to the platform's own handler, so the operator lands back on
                // the login screen instead of staring at Android's "app has stopped".
                runCatching { restart() }
                previous?.uncaughtException(thread, throwable)
            }
            // Any other thread: swallowed. That thread dies; the process does not.
        }
    }

    /** Relaunches [MainActivity] in a fresh task and ends this process behind it. */
    private fun restart() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        Process.killProcess(Process.myPid())
    }

    private companion object {
        const val TAG = "CrashGuard"
    }
}
