package com.synergic.posreports

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** This phone's own id, sent with a payment so its own table event is not notified back to it. */
object DeviceId {
    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences("device", Context.MODE_PRIVATE)
        return prefs.getString("id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("id", it).apply()
        }
    }
}

/**
 * The phone's side of the table notifications - the same notice the POS raises when
 * a phone takes a payment, the other way round: a table paid, billed or cleared on the
 * POS (or paid on another phone) is told to whoever holds this phone.
 */
object PhoneNotifications {

    private const val CHANNEL_ID = "table_events"

    /**
     * Notifies [e] - a live table event from the POS - unless this phone made it. Returns
     * false when there was nothing to show.
     */
    fun show(context: Context, e: JSONObject): Boolean {
        if (e.optString("clientId").isNotBlank() && e.optString("clientId") == DeviceId.get(context)) return false
        val fromPos = e.optString("source") == "pos"
        val table = "Table " + e.optString("table") +
            e.optString("section").takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        val bill = e.optString("billNumber").takeIf { it.isNotBlank() }?.let { "Bill $it" }
        val amount = e.optDouble("amount", 0.0).takeIf { it > 0.0 }
            ?.let { "₹ " + String.format(Locale.US, "%,.2f", it) }
        val (title, text) = when (e.optString("type")) {
            "settled" -> "$table paid ${if (fromPos) "on the POS" else "from another phone"}" to
                (listOfNotNull(bill, e.optString("mode").takeIf { it.isNotBlank() }, amount)
                    .joinToString(", ") + " - table is free")
            "billed" -> "$table bill printed" to
                (listOfNotNull(bill, amount).joinToString(", ") + " - ready for payment")
            "cleared" -> "$table order cleared" to "Cleared on the POS - table is free"
            else -> return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Table updates from the POS", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Tables billed, paid or cleared on the POS" }
            )
        }
        val open = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching {
            NotificationManagerCompat.from(app).notify((e.optLong("orderId") % 100_000).toInt() + 1000, n)
        }
        return true
    }
}
