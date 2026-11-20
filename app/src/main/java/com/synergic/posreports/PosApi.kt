package com.synergic.posreports

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

/** A request the POS answered with an error, or could not be reached for. */
class PosException(
    message: String,
    val unauthorized: Boolean = false,
    /** The POS has no such route - it runs an older version of the till app. */
    val notFound: Boolean = false,
    /** Nobody is logged in on the POS - the phone is disconnected until somebody is. */
    val loggedOut: Boolean = false
) : Exception(message)

/**
 * Talks to the POS's Mobile Reports server (read only).
 *
 * If the POS stops answering - its IP changed after a router restart - it is looked
 * for again on the network ([Discovery]) and, once found, the new address is saved
 * and the request retried, so the user does not have to pair again.
 */
class PosApi(private val context: Context, private val store: ConnectionStore) {

    /**
     * Why the POS last turned this phone away, when it was because its user logged out -
     * shown on the connect screen the app falls back to. Cleared once read.
     */
    @Volatile var lastRefusal: String? = null

    /** Checks [c] really is a SynergicPOS and accepts its PIN. */
    suspend fun verify(c: Connection) {
        val ping = get(c, "/api/ping", auth = false)
        if (ping.optString("app") != "SynergicPOS") throw PosException("That address is not a SynergicPOS")
        get(c, "/api/dashboard")
    }

    suspend fun dashboard(): JSONObject = call("/api/dashboard")

    /** [kind] is bill-wise, item-wise, payment-wise or category-wise; dates are yyyy-MM-dd. */
    suspend fun report(kind: String, from: String, to: String): JSONObject =
        call("/api/reports/$kind?from=${enc(from)}&to=${enc(to)}")

    /** Every report the POS's own Reports screen shows: `{reports: [{id, title}]}`. */
    suspend fun catalog(): JSONObject = call("/api/catalog")

    /**
     * Any report from [catalog], as a table. [from]/[to] are yyyy-MM-dd, or
     * "yyyy-MM-dd HH:mm" for a report asked for by the minute; [filter] and [mode] are
     * one of the options the previous answer offered, or null for the POS's default.
     */
    suspend fun anyReport(id: String, from: String, to: String, filter: String?, mode: String?): JSONObject =
        call(buildString {
            append("/api/report?id=${enc(id)}&from=${enc(from)}&to=${enc(to)}")
            if (!filter.isNullOrBlank()) append("&filter=${enc(filter)}")
            if (!mode.isNullOrBlank()) append("&mode=${enc(mode)}")
        })

    /**
     * Dine-in tables whose bill is printed and waiting for payment (Restaurant mode):
     * `{tables: [{orderId, table, section, amount, time, items, billNumber}], modes: [...]}`.
     */
    suspend fun billedTables(): JSONObject = call("/api/tables/billed")

    /**
     * Confirms payment for one billed table and frees it - the till saves the bill as
     * its own Confirm Payment would. [amount] is what was shown; the till refuses if
     * what is owed has changed. Returns `{ok, billNumber}`.
     *
     * Not retried at a rediscovered address the way reads are: a payment is never sent
     * twice on a guess. (The till would refuse the second anyway - the table is gone.)
     */
    /** A billed table's checkout, as the till's checkout screen shows it. */
    suspend fun tableCheckout(orderId: Long): JSONObject = call("/api/tables/checkout?orderId=$orderId")

    /** The till's Receipt Preview for a billed table paid as [payment], as PNG bytes. */
    suspend fun tablePreview(orderId: Long, payment: TablePayment): ByteArray {
        val c = store.load() ?: throw PosException("Not paired with a POS", unauthorized = true)
        return withContext(Dispatchers.IO) {
            val conn = (URL(c.baseUrl + "/api/tables/preview?orderId=$orderId&" + payment.form())
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000; readTimeout = 30_000
                setRequestProperty("X-POS-PIN", c.pin)
            }
            try {
                val code = conn.responseCode
                if (code in 200..299) conn.inputStream.use { it.readBytes() }
                else {
                    val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val msg = runCatching { JSONObject(body).optString("error") }.getOrNull()
                    throw PosException(msg?.ifBlank { null } ?: "Could not get the receipt ($code)", unauthorized = code == 401)
                }
            } catch (e: IOException) {
                throw PosException("Cannot reach the POS. Check the phone is on the shop Wi-Fi.")
            } finally {
                conn.disconnect()
            }
        }
    }

    suspend fun settleTable(orderId: Long, amount: Double, payment: TablePayment): JSONObject {
        val c = store.load() ?: throw PosException("Not paired with a POS", unauthorized = true)
        // Who is paying, so the POS's table event for it is not notified back to this phone.
        val body = "orderId=$orderId&amount=${enc(TablePayment.num(amount))}&clientId=${enc(DeviceId.get(context))}&" +
            payment.form()
        return try {
            request(c, "/api/tables/settle", method = "POST", body = body)
        } catch (e: IOException) {
            throw PosException("Could not reach the POS - check on the till whether the table was settled before trying again.")
        }
    }

    private suspend fun call(path: String): JSONObject {
        val c = store.load() ?: throw PosException("Not paired with a POS", unauthorized = true)
        return try {
            get(c, path)
        } catch (e: IOException) {
            // Unreachable: the POS may have a new IP. Look for it, then try once more.
            val found = Discovery.find(context) ?: throw PosException(
                "Cannot reach the POS at ${c.ip}. Check the phone is on the shop Wi-Fi and Mobile Reports is on."
            )
            val moved = c.copy(ip = found.first, port = found.second)
            val result = try {
                get(moved, path)
            } catch (e2: IOException) {
                throw PosException("Cannot reach the POS. Check the phone is on the shop Wi-Fi.")
            }
            store.save(moved)
            result
        }
    }

    private suspend fun get(c: Connection, path: String, auth: Boolean = true): JSONObject =
        request(c, path, auth = auth)

    private suspend fun request(
        c: Connection, path: String, auth: Boolean = true, method: String = "GET", body: String? = null
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = (URL(c.baseUrl + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                // A long period on a busy till is real work - give it time.
                readTimeout = 60_000
                requestMethod = method
                if (auth) setRequestProperty("X-POS-PIN", c.pin)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    outputStream.use { it.write(body.toByteArray()) }
                }
            }
            try {
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                when {
                    code in 200..299 && json != null -> json
                    code == 401 && json?.optBoolean("loggedOut") == true -> {
                        val msg = json.optString("error").ifBlank { "The POS user logged out." }
                        lastRefusal = msg
                        throw PosException(msg, unauthorized = true, loggedOut = true)
                    }
                    code == 401 -> throw PosException(
                        "The POS refused the PIN. Scan the QR code on the POS again.", unauthorized = true
                    )
                    code == 429 -> throw PosException("Too many wrong PINs - wait a minute and try again.")
                    code == 404 -> throw PosException(
                        json?.optString("error")?.ifBlank { null } ?: "Not found on the POS", notFound = true
                    )
                    else -> throw PosException(json?.optString("error")?.ifBlank { null } ?: "POS error ($code)")
                }
            } finally {
                conn.disconnect()
            }
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/**
 * How a table was paid, in the till's checkout terms: a tile ([mode] - Cash, Card,
 * Online, Credit), the cash handed over or the amount paid now on credit ([tendered]),
 * a split across cash / UPI / card, and the account a credit sale goes on.
 */
data class TablePayment(
    val mode: String,
    val tendered: Double = 0.0,
    val splitCash: Double = 0.0,
    val splitOnline: Double = 0.0,
    val splitCard: Double = 0.0,
    val creditPhone: String? = null
) {
    fun form(): String = buildString {
        append("mode=").append(URLEncoder.encode(mode, "UTF-8"))
        append("&tendered=").append(num(tendered))
        if (splitCash > 0) append("&splitCash=").append(num(splitCash))
        if (splitOnline > 0) append("&splitOnline=").append(num(splitOnline))
        if (splitCard > 0) append("&splitCard=").append(num(splitCard))
        creditPhone?.let { append("&creditPhone=").append(URLEncoder.encode(it, "UTF-8")) }
    }

    companion object {
        fun num(v: Double) = "%.2f".format(java.util.Locale.US, v)
    }
}

/** Finds a POS announcing Mobile Reports on the network (mDNS / NSD). */
object Discovery {
    private const val SERVICE_TYPE = "_synergicpos._tcp."

    /** The first POS found as (ip, port), or null if none answers within [timeoutMs]. */
    suspend fun find(context: Context, timeoutMs: Long = 8_000): Pair<String, Int>? {
        val app = context.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        // Some phones drop multicast packets unless an app holds this lock.
        val lock = runCatching { wifi?.createMulticastLock("posreports")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
        try {
            return withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    var listener: NsdManager.DiscoveryListener? = null
                    fun stop() = runCatching { listener?.let { nsd.stopServiceDiscovery(it) } }
                    listener = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(serviceType: String) {}
                        override fun onDiscoveryStopped(serviceType: String) {}
                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                        override fun onServiceLost(info: NsdServiceInfo) {}
                        override fun onServiceFound(info: NsdServiceInfo) {
                            if (!info.serviceType.contains("_synergicpos")) return
                            @Suppress("DEPRECATION")
                            nsd.resolveService(info, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                                override fun onServiceResolved(info: NsdServiceInfo) {
                                    @Suppress("DEPRECATION")
                                    val ip = info.host?.hostAddress
                                    if (ip != null && Connection.isIp(ip) && cont.isActive) {
                                        stop()
                                        cont.resume(ip to info.port)
                                    }
                                }
                            })
                        }
                    }
                    nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                    cont.invokeOnCancellation { stop() }
                }
            }
        } finally {
            runCatching { lock?.release() }
        }
    }
}
