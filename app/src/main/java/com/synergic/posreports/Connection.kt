package com.synergic.posreports

import android.content.Context
import android.net.Uri

/** Where the POS is and the PIN it wants - everything a report request needs. */
data class Connection(val ip: String, val port: Int, val pin: String) {
    val baseUrl: String get() = "http://$ip:$port"

    companion object {
        const val DEFAULT_PORT = 8765

        /**
         * Reads the POS's pairing QR code: `synergicpos://connect?ip=...&port=...&pin=...`.
         * Null for anything else, so a stray QR code is not taken for a POS.
         */
        fun fromQr(text: String?): Connection? {
            if (text.isNullOrBlank()) return null
            val uri = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return null
            if (!uri.scheme.equals("synergicpos", true) || !uri.host.equals("connect", true)) return null
            val ip = uri.getQueryParameter("ip")?.trim().orEmpty()
            val pin = uri.getQueryParameter("pin")?.trim().orEmpty()
            val port = uri.getQueryParameter("port")?.toIntOrNull() ?: DEFAULT_PORT
            if (!isIp(ip) || !isPin(pin)) return null
            return Connection(ip, port, pin)
        }

        fun isIp(s: String): Boolean {
            val parts = s.split(".")
            return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
        }

        fun isPin(s: String): Boolean = s.length == 6 && s.all { it.isDigit() }
    }
}

/** The saved connection, kept on the phone so it is only paired once. */
class ConnectionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pos_connection", Context.MODE_PRIVATE)

    fun load(): Connection? {
        val ip = prefs.getString("ip", null) ?: return null
        val pin = prefs.getString("pin", null) ?: return null
        return Connection(ip, prefs.getInt("port", Connection.DEFAULT_PORT), pin)
    }

    fun save(c: Connection) {
        prefs.edit().putString("ip", c.ip).putInt("port", c.port).putString("pin", c.pin).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
