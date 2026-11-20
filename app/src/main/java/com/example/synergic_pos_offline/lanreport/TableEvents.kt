package com.example.synergic_pos_offline.lanreport

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.synergic_pos_offline.MainActivity
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * A table changed hands - billed, paid, or cleared - and everyone looking at tables
 * needs to know: the till's own restaurant screens (in this process, through
 * [addListener]) and every phone connected to Mobile Reports (over the event socket,
 * [EventSocketServer]).
 *
 * A payment taken on a phone also raises a notification on the till, so the cashier
 * sees it whatever screen they are on.
 */
object TableEvents {

    const val BILLED = "billed"
    const val SETTLED = "settled"
    const val CLEARED = "cleared"

    /** Where the change was made: on a phone through Mobile Reports, or on the till. */
    const val FROM_MOBILE = "mobile"
    const val FROM_POS = "pos"

    data class Event(
        val type: String,
        val orderId: Long,
        val table: String,
        val section: String,
        val source: String,
        val billNumber: String = "",
        /** How it was paid - "Cash", "Card", "Online", "Credit", "Split". */
        val mode: String = "",
        val amount: Double = 0.0,
        /**
         * The phone that made the change, as it identified itself - so that phone can
         * tell its own payment from another's and not notify itself. Blank for the till.
         */
        val clientId: String = ""
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("type", type).put("orderId", orderId).put("table", table).put("section", section)
            .put("source", source).put("billNumber", billNumber).put("mode", mode).put("amount", amount)
            .put("clientId", clientId)

        /** "Table 3 (Family Room)". */
        val tableLabel: String get() = "Table $table" + if (section.isBlank()) "" else " ($section)"
    }

    private val listeners = CopyOnWriteArraySet<(Event) -> Unit>()

    /** Called on whatever thread published - a listener posts to its own screen. */
    fun addListener(l: (Event) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Event) -> Unit) { listeners.remove(l) }

    fun publish(context: Context, e: Event) {
        listeners.forEach { l -> runCatching { l(e) }.onFailure { Log.w(TAG, "Listener failed", it) } }
        EventSocketServer.broadcast(e.toJson())
        if (e.source == FROM_MOBILE && e.type == SETTLED) notifyPaid(context.applicationContext, e)
    }

    /** "Table 3 (Family Room) paid from mobile - Bill 109, Card, ₹ 280.00". */
    private fun notifyPaid(context: Context, e: Event) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Table payments from mobile", NotificationManager.IMPORTANCE_HIGH)
                        .apply { description = "A table settled from the Mobile Reports app" }
                )
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val detail = listOfNotNull(
                e.billNumber.takeIf { it.isNotBlank() }?.let { "Bill $it" },
                e.mode.takeIf { it.isNotBlank() },
                "₹ " + String.format(java.util.Locale.US, "%,.2f", e.amount)
            ).joinToString(", ")
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("${e.tableLabel} paid from mobile")
                .setContentText("$detail - table is free")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_BASE + (e.orderId % 1000).toInt(), n)
        }.onFailure { Log.w(TAG, "Could not show the payment notification", it) }
    }

    private const val TAG = "TableEvents"
    private const val CHANNEL_ID = "table_payments"
    private const val NOTIFICATION_BASE = 9000
}
