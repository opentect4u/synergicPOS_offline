package com.example.synergic_pos_offline.lanreport

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * What a phone needs to reach this till's Mobile Reports: whether sharing is on, the
 * PIN, the till's address on the shop network, and the QR code text that carries them.
 *
 * Kept in its own preferences file so nothing else in the app reads or writes it.
 */
object LanPairing {

    /** The port the report server listens on. */
    const val PORT = 8765

    /** The name the till announces itself under on the network (NSD / mDNS). */
    const val SERVICE_TYPE = "_synergicpos._tcp."

    private const val PREFS = "lan_report"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PIN = "pin"

    private val random = SecureRandom()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** The current 6-digit PIN, made on first use. */
    fun pin(context: Context): String =
        prefs(context).getString(KEY_PIN, null)?.takeIf { it.length == 6 } ?: newPin(context)

    /**
     * Replaces the PIN. Every phone paired with the old one stops getting reports
     * until it is paired again - what to do when a phone is lost.
     */
    fun newPin(context: Context): String {
        val pin = (random.nextInt(900_000) + 100_000).toString()
        prefs(context).edit().putString(KEY_PIN, pin).apply()
        return pin
    }

    /** Whether [given] is the PIN. Compared in constant time. */
    fun pinMatches(context: Context, given: String?): Boolean {
        if (given.isNullOrBlank()) return false
        return MessageDigest.isEqual(given.trim().toByteArray(), pin(context).toByteArray())
    }

    /**
     * This till's IPv4 address on the shop network - Wi-Fi or a LAN cable, whichever
     * is connected - or null when it is on neither.
     */
    fun localIp(context: Context): String? {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching {
            cm?.getLinkProperties(cm.activeNetwork)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()?.let { return it }

        // Fallback: any up, non-loopback interface with a private IPv4 address.
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }

    /** The text the pairing QR code carries. The mobile app reads ip, port and pin from it. */
    fun qrPayload(ip: String, pin: String): String =
        "synergicpos://connect?ip=$ip&port=$PORT&pin=$pin"
}
