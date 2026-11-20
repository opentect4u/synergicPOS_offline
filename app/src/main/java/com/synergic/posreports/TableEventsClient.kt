package com.synergic.posreports

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * The POS's live table events, over its event socket: one JSON object per line -
 * `{"type":"billed"|"settled"|"cleared", orderId, table, section, source, billNumber,
 * mode, amount}` - plus a `ping` every 20 seconds.
 *
 * Connects with the paired PIN, and reconnects on its own (every few seconds) when
 * Wi-Fi drops or the POS restarts, for as long as the flow is collected. The port is
 * the one the POS announces at `/api/ping`, or the one after its report port.
 */
class TableEventsClient(private val store: ConnectionStore) {

    /** Events as they arrive; pings are filtered out. Collect while the app is open. */
    fun events(): Flow<JSONObject> = callbackFlow {
        val current = java.util.concurrent.atomic.AtomicReference<Socket?>(null)
        val alive = java.util.concurrent.atomic.AtomicBoolean(true)

        val worker = Thread({
            while (alive.get()) {
                val c = store.load() ?: break
                try {
                    val port = eventsPort(c) ?: (c.port + 1)
                    val s = Socket()
                    current.set(s)
                    s.connect(InetSocketAddress(c.ip, port), 5_000)
                    // Three missed heartbeats and the POS is taken to be gone.
                    s.soTimeout = 65_000
                    s.getOutputStream().write("PIN ${c.pin}\n".toByteArray())
                    s.getOutputStream().flush()
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                    while (alive.get()) {
                        val line = reader.readLine() ?: break
                        val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        when (o.optString("type")) {
                            "ping", "hello" -> Unit
                            "error" -> break   // wrong PIN or locked out - try again later
                            else -> trySendBlocking(o)
                        }
                    }
                } catch (_: Exception) {
                    // Unreachable, timed out or closed - retried below.
                } finally {
                    runCatching { current.getAndSet(null)?.close() }
                }
                if (alive.get()) runCatching { Thread.sleep(RETRY_MS) }
            }
        }, "pos-table-events").apply { isDaemon = true; start() }

        awaitClose {
            alive.set(false)
            runCatching { current.getAndSet(null)?.close() }
            worker.interrupt()
        }
    }

    /** The event port the POS announces at /api/ping, or null if it does not say. */
    private fun eventsPort(c: Connection): Int? = runCatching {
        val conn = (URL(c.baseUrl + "/api/ping").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000; readTimeout = 5_000
        }
        try {
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                .optInt("eventsPort", 0).takeIf { it > 0 }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private companion object {
        const val RETRY_MS = 3_000L
    }
}
